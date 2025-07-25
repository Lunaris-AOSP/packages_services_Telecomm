/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import static android.Manifest.permission.MODIFY_PHONE_STATE;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.Log;
import android.telecom.Logging.Session;
import android.telecom.Logging.Runnable;
import android.telecom.ParcelableConference;
import android.telecom.ParcelableConnection;
import android.telecom.PhoneAccountHandle;
import android.telecom.QueryLocationException;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.CellIdentity;
import android.telephony.TelephonyManager;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telecom.IConnectionService;
import com.android.internal.telecom.IConnectionServiceAdapter;
import com.android.internal.telecom.IVideoProvider;
import com.android.internal.telecom.RemoteServiceCallback;
import com.android.internal.util.Preconditions;
import com.android.server.telecom.flags.FeatureFlags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/**
 * Wrapper for {@link IConnectionService}s, handles binding to {@link IConnectionService} and keeps
 * track of when the object can safely be unbound. Other classes should not use
 * {@link IConnectionService} directly and instead should use this class to invoke methods of
 * {@link IConnectionService}.
 */
@VisibleForTesting
public class ConnectionServiceWrapper extends ServiceBinder implements
        ConnectionServiceFocusManager.ConnectionServiceFocus, CallSourceService {

    /**
     * Anomaly Report UUIDs and corresponding error descriptions specific to
     * ConnectionServiceWrapper.
     */
    public static final UUID CREATE_CONNECTION_TIMEOUT_ERROR_UUID =
            UUID.fromString("54b7203d-a79f-4cbd-b639-85cd93a39cbb");
    public static final String CREATE_CONNECTION_TIMEOUT_ERROR_MSG =
            "Timeout expired before Telecom connection was created.";
    public static final UUID CREATE_CONFERENCE_TIMEOUT_ERROR_UUID =
            UUID.fromString("caafe5ea-2472-4c61-b2d8-acb9d47e13dd");
    public static final String CREATE_CONFERENCE_TIMEOUT_ERROR_MSG =
            "Timeout expired before Telecom conference was created.";
    public static final UUID NULL_SCHEDULED_EXECUTOR_ERROR_UUID =
            UUID.fromString("af6b293b-239f-4ccf-bf3a-db212594e29d");
    public static final String NULL_SCHEDULED_EXECUTOR_ERROR_MSG =
            "Scheduled executor is null when creating connection/conference.";
    public static final UUID EXECUTOR_REJECTED_EXECUTION_ERROR_UUID =
            UUID.fromString("649b348c-9d3f-451e-bae9-d9920e7b422c");

    public static final String EXECUTOR_REJECTED_EXECUTION_ERROR_MSG =
            "Scheduled executor caused a Rejected Execution Exception when creating connection.";

    private static final String TELECOM_ABBREVIATION = "cast";
    private static final long SERVICE_BINDING_TIMEOUT = 15000L;
    private CompletableFuture<Pair<Integer, Location>> mQueryLocationFuture = null;
    private @Nullable CancellationSignal mOngoingQueryLocationRequest = null;
    private final ExecutorService mQueryLocationExecutor = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService mScheduledExecutor =
            Executors.newSingleThreadScheduledExecutor();
    // Pre-allocate space for 2 calls; realistically thats all we should ever need (tm)
    private final Map<Call, ScheduledFuture<?>> mScheduledFutureMap = new ConcurrentHashMap<>(2);
    private AnomalyReporterAdapter mAnomalyReporter = new AnomalyReporterAdapterImpl();

    private final class Adapter extends IConnectionServiceAdapter.Stub {

        @Override
        public void handleCreateConnectionComplete(String callId, ConnectionRequest request,
                ParcelableConnection connection, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_HANDLE_CREATE_CONNECTION_COMPLETE,
                    mPackageAbbreviation);
            UserHandle callingUserHandle = Binder.getCallingUserHandle();
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("handleCreateConnectionComplete %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    maybeRemoveCleanupFuture(call);
                    // Check status hints image for cross user access
                    if (connection.getStatusHints() != null) {
                        Icon icon = connection.getStatusHints().getIcon();
                        connection.getStatusHints().setIcon(StatusHints.
                                validateAccountIconUserBoundary(icon, callingUserHandle));
                    }
                    ConnectionServiceWrapper.this
                            .handleCreateConnectionComplete(callId, request, connection);

                    if (mServiceInterface != null) {
                        logOutgoing("createConnectionComplete %s", callId);
                        try {
                            mServiceInterface.createConnectionComplete(callId,
                                    Log.getExternalSession());
                        } catch (RemoteException e) {
                            logOutgoing("createConnectionComplete remote exception=%s", e);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void handleCreateConferenceComplete(String callId, ConnectionRequest request,
                ParcelableConference conference, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_HANDLE_CREATE_CONNECTION_COMPLETE,
                    mPackageAbbreviation);
            UserHandle callingUserHandle = Binder.getCallingUserHandle();
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("handleCreateConferenceComplete %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    maybeRemoveCleanupFuture(call);
                    // Check status hints image for cross user access
                    if (conference.getStatusHints() != null) {
                        Icon icon = conference.getStatusHints().getIcon();
                        conference.getStatusHints().setIcon(StatusHints.
                                validateAccountIconUserBoundary(icon, callingUserHandle));
                    }
                    ConnectionServiceWrapper.this
                            .handleCreateConferenceComplete(callId, request, conference);

                    if (mServiceInterface != null) {
                        logOutgoing("createConferenceComplete %s", callId);
                        try {
                            mServiceInterface.createConferenceComplete(callId,
                                    Log.getExternalSession());
                        } catch (RemoteException e) {
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }


        @Override
        public void setActive(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_SET_ACTIVE,
                    mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setActive %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        mCallsManager.markCallAsActive(call);
                    } else {
                        // Log.w(this, "setActive, unknown call id: %s", msg.obj);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setRinging(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_SET_RINGING, mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setRinging %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        mCallsManager.markCallAsRinging(call);
                    } else {
                        // Log.w(this, "setRinging, unknown call id: %s", msg.obj);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void resetConnectionTime(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.rCCT", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("resetConnectionTime %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        mCallsManager.resetConnectionTime(call);
                    } else {
                        // Log.w(this, "resetConnectionTime, unknown call id: %s", msg.obj);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setVideoProvider(String callId, IVideoProvider videoProvider,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sVP", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setVideoProvider %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setVideoProvider(videoProvider);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setDialing(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_SET_DIALING, mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setDialing %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        mCallsManager.markCallAsDialing(call);
                    } else {
                        // Log.w(this, "setDialing, unknown call id: %s", msg.obj);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setPulling(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_SET_PULLING, mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setPulling %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        mCallsManager.markCallAsPulling(call);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setDisconnected(String callId, DisconnectCause disconnectCause,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_SET_DISCONNECTED,
                    mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setDisconnected %s %s", callId, disconnectCause);
                    Call call = mCallIdMapper.getCall(callId);
                    Log.d(this, "disconnect call %s %s", disconnectCause, call);
                    if (call != null) {
                        mCallsManager.markCallAsDisconnected(call, disconnectCause);
                    } else {
                        // Log.w(this, "setDisconnected, unknown call id: %s", args.arg1);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setOnHold(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_SET_ON_HOLD, mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setOnHold %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        mCallsManager.markCallAsOnHold(call);
                    } else {
                        // Log.w(this, "setOnHold, unknown call id: %s", msg.obj);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setRingbackRequested(String callId, boolean ringback,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.SRR", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setRingbackRequested %s %b", callId, ringback);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setRingbackRequested(ringback);
                    } else {
                        // Log.w(this, "setRingback, unknown call id: %s", args.arg1);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void removeCall(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_REMOVE_CALL, mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("removeCall %s", callId);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        boolean isRemovalPending = mFlags.cancelRemovalOnEmergencyRedial()
                                && call.isRemovalPending();
                        if (call.isAlive() && !call.isDisconnectHandledViaFuture()
                                && !isRemovalPending) {
                            Log.w(this, "call not disconnected when removeCall"
                                    + " called, marking disconnected first.");
                            mCallsManager.markCallAsDisconnected(
                                    call, new DisconnectCause(DisconnectCause.REMOTE));
                        }
                        mCallsManager.markCallAsRemoved(call);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setConnectionCapabilities(String callId, int connectionCapabilities,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sCC", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setConnectionCapabilities %s %d", callId, connectionCapabilities);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setConnectionCapabilities(connectionCapabilities);
                    } else {
                        // Log.w(ConnectionServiceWrapper.this,
                        // "setConnectionCapabilities, unknown call id: %s", msg.obj);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setConnectionProperties(String callId, int connectionProperties,
                Session.Info sessionInfo) {
            Log.startSession("CSW.sCP", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setConnectionProperties %s %d", callId, connectionProperties);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setConnectionProperties(connectionProperties);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setIsConferenced(String callId, String conferenceCallId,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_SET_IS_CONFERENCED,
                    mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setIsConferenced %s %s", callId, conferenceCallId);
                    Call childCall = mCallIdMapper.getCall(callId);
                    if (childCall != null) {
                        if (conferenceCallId == null) {
                            Log.d(this, "unsetting parent: %s", conferenceCallId);
                            childCall.setParentAndChildCall(null);
                        } else {
                            Call conferenceCall = mCallIdMapper.getCall(conferenceCallId);
                            // In a situation where a cmgr is used, the conference should be tracked
                            // by that cmgr's instance of CSW. The cmgr instance of CSW will track
                            // and properly set the parent and child calls so the request from the
                            // original Telephony instance of CSW can be ignored.
                            if (conferenceCall != null){
                                childCall.setParentAndChildCall(conferenceCall);
                            }
                        }
                    } else {
                        // Log.w(this, "setIsConferenced, unknown call id: %s", args.arg1);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setConferenceMergeFailed(String callId, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sCMF", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setConferenceMergeFailed %s", callId);
                    // TODO: we should move the UI for indication a merge failure here
                    // from CallNotifier.onSuppServiceFailed(). This way the InCallUI can
                    // deliver the message anyway that they want. b/20530631.
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.onConnectionEvent(Connection.EVENT_CALL_MERGE_FAILED, null);
                    } else {
                        Log.w(this, "setConferenceMergeFailed, unknown call id: %s", callId);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void addConferenceCall(String callId, ParcelableConference parcelableConference,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, LogUtils.Sessions.CSW_ADD_CONFERENCE_CALL,
                    mPackageAbbreviation);

            UserHandle callingUserHandle = Binder.getCallingUserHandle();
            // Check status hints image for cross user access
            if (parcelableConference.getStatusHints() != null) {
                Icon icon = parcelableConference.getStatusHints().getIcon();
                parcelableConference.getStatusHints().setIcon(StatusHints
                        .validateAccountIconUserBoundary(icon, callingUserHandle));
            }

            if (parcelableConference.getConnectElapsedTimeMillis() != 0
                    && mContext.checkCallingOrSelfPermission(MODIFY_PHONE_STATE)
                            != PackageManager.PERMISSION_GRANTED) {
                Log.w(this, "addConferenceCall from caller without permission!");
                parcelableConference = new ParcelableConference.Builder(
                        parcelableConference.getPhoneAccount(),
                        parcelableConference.getState())
                        .setConnectionCapabilities(parcelableConference.getConnectionCapabilities())
                        .setConnectionProperties(parcelableConference.getConnectionProperties())
                        .setConnectionIds(parcelableConference.getConnectionIds())
                        .setVideoAttributes(parcelableConference.getVideoProvider(),
                                parcelableConference.getVideoState())
                        .setStatusHints(parcelableConference.getStatusHints())
                        .setExtras(parcelableConference.getExtras())
                        .setAddress(parcelableConference.getHandle(),
                                parcelableConference.getHandlePresentation())
                        // no caller display name set.
                        .setDisconnectCause(parcelableConference.getDisconnectCause())
                        .setRingbackRequested(parcelableConference.isRingbackRequested())
                        .build();
            }

            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mCallIdMapper.getCall(callId) != null) {
                        Log.w(this, "Attempting to add a conference call using an existing " +
                                "call id %s", callId);
                        return;
                    }
                    logIncoming("addConferenceCall %s %s [%s]", callId, parcelableConference,
                            parcelableConference.getConnectionIds());

                    // Make sure that there's at least one valid call. For remote connections
                    // we'll get a add conference msg from both the remote connection service
                    // and from the real connection service.
                    boolean hasValidCalls = false;
                    for (String connId : parcelableConference.getConnectionIds()) {
                        if (mCallIdMapper.getCall(connId) != null) {
                            hasValidCalls = true;
                        }
                    }
                    // But don't bail out if the connection count is 0, because that is a valid
                    // IMS conference state.
                    if (!hasValidCalls && parcelableConference.getConnectionIds().size() > 0) {
                        Log.d(this, "Attempting to add a conference with no valid calls");
                        return;
                    }

                    PhoneAccountHandle phAcc = null;
                    if (parcelableConference != null &&
                            parcelableConference.getPhoneAccount() != null) {
                        phAcc = parcelableConference.getPhoneAccount();
                    }

                    Bundle connectionExtras = parcelableConference.getExtras();

                    String connectIdToCheck = null;
                    if (connectionExtras != null && connectionExtras
                            .containsKey(Connection.EXTRA_ORIGINAL_CONNECTION_ID)) {
                        // Conference was added via a connection manager, see if its original id is
                        // known.
                        connectIdToCheck = connectionExtras
                                .getString(Connection.EXTRA_ORIGINAL_CONNECTION_ID);
                    } else {
                        connectIdToCheck = callId;
                    }

                    Call conferenceCall;
                    // Check to see if this conference has already been added.
                    Call alreadyAddedConnection = mCallsManager
                            .getAlreadyAddedConnection(connectIdToCheck);
                    if (alreadyAddedConnection != null && mCallIdMapper.getCall(callId) == null) {
                        // We are currently attempting to add the conference via a connection mgr,
                        // and the originating ConnectionService has already added it.  Instead of
                        // making a new Telecom call, we will simply add it to the ID mapper here,
                        // and replace the ConnectionService on the call.
                        mCallIdMapper.addCall(alreadyAddedConnection, callId);
                        alreadyAddedConnection.replaceConnectionService(
                                ConnectionServiceWrapper.this);
                        conferenceCall = alreadyAddedConnection;
                    } else {
                        // need to create a new Call
                        Call newConferenceCall = mCallsManager.createConferenceCall(callId,
                                phAcc, parcelableConference);
                        mCallIdMapper.addCall(newConferenceCall, callId);
                        newConferenceCall.setConnectionService(ConnectionServiceWrapper.this);
                        conferenceCall = newConferenceCall;
                    }

                    Log.d(this, "adding children to conference %s phAcc %s",
                            parcelableConference.getConnectionIds(), phAcc);
                    for (String connId : parcelableConference.getConnectionIds()) {
                        Call childCall = mCallIdMapper.getCall(connId);
                        Log.d(this, "found child: %s", connId);
                        if (childCall != null) {
                            childCall.setParentAndChildCall(conferenceCall);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void onPostDialWait(String callId, String remaining,
                Session.Info sessionInfo) throws RemoteException {
            Log.startSession(sessionInfo, "CSW.oPDW", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("onPostDialWait %s %s", callId, remaining);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.onPostDialWait(remaining);
                    } else {
                        // Log.w(this, "onPostDialWait, unknown call id: %s", args.arg1);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void onPostDialChar(String callId, char nextChar,
                Session.Info sessionInfo) throws RemoteException {
            Log.startSession(sessionInfo, "CSW.oPDC", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("onPostDialChar %s %s", callId, nextChar);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.onPostDialChar(nextChar);
                    } else {
                        // Log.w(this, "onPostDialChar, unknown call id: %s", args.arg1);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void queryRemoteConnectionServices(RemoteServiceCallback callback,
                String callingPackage, Session.Info sessionInfo) {
            final UserHandle callingUserHandle = Binder.getCallingUserHandle();
            Log.startSession(sessionInfo, "CSW.qRCS", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("queryRemoteConnectionServices callingPackage=" + callingPackage);
                    ConnectionServiceWrapper.this
                            .queryRemoteConnectionServices(callingUserHandle, callingPackage,
                                    callback);
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setVideoState(String callId, int videoState, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sVS", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setVideoState %s %d", callId, videoState);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setVideoState(videoState);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setIsVoipAudioMode(String callId, boolean isVoip, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sIVAM", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setIsVoipAudioMode %s %b", callId, isVoip);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setIsVoipAudioMode(isVoip);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setAudioRoute(String callId, int audioRoute,
                String bluetoothAddress, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sAR", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setAudioRoute %s %s", callId,
                            CallAudioState.audioRouteToString(audioRoute));
                    mCallsManager.setAudioRoute(audioRoute, bluetoothAddress);
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void requestCallEndpointChange(String callId, CallEndpoint endpoint,
                ResultReceiver callback, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.rCEC", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("requestCallEndpointChange %s %s", callId,
                            endpoint.getEndpointName());
                    mCallsManager.requestCallEndpointChange(endpoint, callback);
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setStatusHints(String callId, StatusHints statusHints,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sSH", mPackageAbbreviation);
            UserHandle callingUserHandle = Binder.getCallingUserHandle();
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setStatusHints %s %s", callId, statusHints);
                    // Check status hints image for cross user access
                    if (statusHints != null) {
                        Icon icon = statusHints.getIcon();
                        statusHints.setIcon(StatusHints.validateAccountIconUserBoundary(
                                icon, callingUserHandle));
                    }
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setStatusHints(statusHints);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void putExtras(String callId, Bundle extras, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.pE", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    Bundle.setDefusable(extras, true);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.putConnectionServiceExtras(extras);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void removeExtras(String callId, List<String> keys, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.rE", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("removeExtra %s %s", callId, keys);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.removeExtras(Call.SOURCE_CONNECTION_SERVICE, keys);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setAddress(String callId, Uri address, int presentation,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sA", mPackageAbbreviation);

            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setAddress %s %s %d", callId, address, presentation);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setHandle(address, presentation);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setCallerDisplayName(String callId, String callerDisplayName, int presentation,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sCDN", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setCallerDisplayName %s %s %d", callId, callerDisplayName,
                            presentation);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setCallerDisplayName(callerDisplayName, presentation);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setConferenceableConnections(String callId, List<String> conferenceableCallIds,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sCC", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {

                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        logIncoming("setConferenceableConnections %s %s", callId,
                                conferenceableCallIds);
                        List<Call> conferenceableCalls =
                                new ArrayList<>(conferenceableCallIds.size());
                        for (String otherId : conferenceableCallIds) {
                            Call otherCall = mCallIdMapper.getCall(otherId);
                            if (otherCall != null && otherCall != call) {
                                conferenceableCalls.add(otherCall);
                            }
                        }
                        call.setConferenceableCalls(conferenceableCalls);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void addExistingConnection(String callId, ParcelableConnection connection,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.aEC", mPackageAbbreviation);
            UserHandle userHandle = Binder.getCallingUserHandle();
            // Check that the Calling Package matches PhoneAccountHandle's Component Package
            PhoneAccountHandle callingPhoneAccountHandle = connection.getPhoneAccount();
            if (callingPhoneAccountHandle != null) {
                mAppOpsManager.checkPackage(Binder.getCallingUid(),
                        callingPhoneAccountHandle.getComponentName().getPackageName());
            }

            boolean hasCrossUserAccess = mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS)
                    == PackageManager.PERMISSION_GRANTED;
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    // Make sure that the PhoneAccount associated with the incoming
                    // ParcelableConnection is in fact registered to Telecom and is being called
                    // from the correct user.
                    List<PhoneAccountHandle> accountHandles =
                    // Include CAPABILITY_EMERGENCY_CALLS_ONLY in this list in case we are adding
                    // an emergency call.
                            mPhoneAccountRegistrar.getCallCapablePhoneAccounts(null /*uriScheme*/,
                            false /*includeDisabledAccounts*/, userHandle, 0 /*capabilities*/,
                            0 /*excludedCapabilities*/, hasCrossUserAccess);
                    PhoneAccountHandle phoneAccountHandle = null;
                    for (PhoneAccountHandle accountHandle : accountHandles) {
                        if(accountHandle.equals(callingPhoneAccountHandle)) {
                            phoneAccountHandle = accountHandle;
                        }
                    }
                    // Allow the Sim call manager account as well, even if its disabled.
                    if (phoneAccountHandle == null && callingPhoneAccountHandle != null) {
                        // Search all SIM PhoneAccounts to see if there is a SIM call manager
                        // associated with any of them and verify that the calling handle matches.
                        for (PhoneAccountHandle handle :
                                mPhoneAccountRegistrar.getSimPhoneAccounts(userHandle)) {
                            int subId = mPhoneAccountRegistrar.getSubscriptionIdForPhoneAccount(
                                    handle);
                            PhoneAccountHandle connectionMgrHandle =
                                    mPhoneAccountRegistrar.getSimCallManager(subId, userHandle);
                            if (callingPhoneAccountHandle.equals(connectionMgrHandle)) {
                                phoneAccountHandle = connectionMgrHandle;
                                break;
                            }
                        }
                    }
                    if (phoneAccountHandle != null) {
                        logIncoming("addExistingConnection %s %s", callId, connection);

                        Bundle connectionExtras = connection.getExtras();
                        String connectIdToCheck = null;
                        if (connectionExtras != null && connectionExtras
                                .containsKey(Connection.EXTRA_ORIGINAL_CONNECTION_ID)) {
                            connectIdToCheck = connectionExtras
                                    .getString(Connection.EXTRA_ORIGINAL_CONNECTION_ID);
                        } else {
                            connectIdToCheck = callId;
                        }

                        // Check status hints image for cross user access
                        if (connection.getStatusHints() != null) {
                            Icon icon = connection.getStatusHints().getIcon();
                            connection.getStatusHints().setIcon(StatusHints.
                                    validateAccountIconUserBoundary(icon, userHandle));
                        }
                        // Handle the case where an existing connection was added by Telephony via
                        // a connection manager.  The remote connection service API does not include
                        // the ability to specify a parent connection when adding an existing
                        // connection, so we stash the desired parent in the connection extras.
                        if (connectionExtras != null
                                && connectionExtras.containsKey(
                                        Connection.EXTRA_ADD_TO_CONFERENCE_ID)
                                && connection.getParentCallId() == null) {
                            String parentId = connectionExtras.getString(
                                    Connection.EXTRA_ADD_TO_CONFERENCE_ID);
                            Log.i(ConnectionServiceWrapper.this, "addExistingConnection: remote "
                                    + "connection will auto-add to parent %s", parentId);
                            // Replace parcelable connection instance, swapping the new desired
                            // parent in.
                            connection = new ParcelableConnection(
                                    connection.getPhoneAccount(),
                                    connection.getState(),
                                    connection.getConnectionCapabilities(),
                                    connection.getConnectionProperties(),
                                    connection.getSupportedAudioRoutes(),
                                    connection.getHandle(),
                                    connection.getHandlePresentation(),
                                    connection.getCallerDisplayName(),
                                    connection.getCallerDisplayNamePresentation(),
                                    connection.getVideoProvider(),
                                    connection.getVideoState(),
                                    connection.isRingbackRequested(),
                                    connection.getIsVoipAudioMode(),
                                    connection.getConnectTimeMillis(),
                                    connection.getConnectElapsedTimeMillis(),
                                    connection.getStatusHints(),
                                    connection.getDisconnectCause(),
                                    connection.getConferenceableConnectionIds(),
                                    connection.getExtras(),
                                    parentId,
                                    connection.getCallDirection(),
                                    connection.getCallerNumberVerificationStatus());
                        }
                        // Check to see if this Connection has already been added.
                        Call alreadyAddedConnection = mCallsManager
                                .getAlreadyAddedConnection(connectIdToCheck);

                        if (alreadyAddedConnection != null
                                && mCallIdMapper.getCall(callId) == null) {
                            if (!Objects.equals(connection.getHandle(),
                                    alreadyAddedConnection.getHandle())) {
                                alreadyAddedConnection.setHandle(connection.getHandle());
                            }
                            if (connection.getHandlePresentation() !=
                                    alreadyAddedConnection.getHandlePresentation()) {
                                alreadyAddedConnection.setHandle(connection.getHandle(),
                                        connection.getHandlePresentation());
                            }
                            if (!Objects.equals(connection.getCallerDisplayName(),
                                    alreadyAddedConnection.getCallerDisplayName())) {
                                alreadyAddedConnection.setCallerDisplayName(connection
                                                .getCallerDisplayName(),
                                        connection.getCallerDisplayNamePresentation());
                            }
                            if (connection.getConnectionCapabilities() !=
                                    alreadyAddedConnection.getConnectionCapabilities()) {
                                alreadyAddedConnection.setConnectionCapabilities(connection
                                        .getConnectionCapabilities());
                            }
                            if (connection.getConnectionProperties() !=
                                    alreadyAddedConnection.getConnectionProperties()) {
                                alreadyAddedConnection.setConnectionCapabilities(connection
                                        .getConnectionProperties());
                            }
                            mCallIdMapper.addCall(alreadyAddedConnection, callId);
                            alreadyAddedConnection
                                    .replaceConnectionService(ConnectionServiceWrapper.this);
                            return;
                        }

                        Call existingCall = mCallsManager
                                .createCallForExistingConnection(callId, connection);
                        mCallIdMapper.addCall(existingCall, callId);
                        existingCall.setConnectionService(ConnectionServiceWrapper.this);
                    } else {
                        Log.e(this, new RemoteException("The PhoneAccount being used is not " +
                                "currently registered with Telecom."), "Unable to " +
                                "addExistingConnection.");
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void onConnectionEvent(String callId, String event, Bundle extras,
                Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.oCE", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    Bundle.setDefusable(extras, true);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.onConnectionEvent(event, extras);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void onRttInitiationSuccess(String callId, Session.Info sessionInfo)
                throws RemoteException {

        }

        @Override
        public void onRttInitiationFailure(String callId, int reason, Session.Info sessionInfo)
                throws RemoteException {
            Log.startSession(sessionInfo, "CSW.oRIF", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.onRttConnectionFailure(reason);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void onRttSessionRemotelyTerminated(String callId, Session.Info sessionInfo)
                throws RemoteException {

        }

        @Override
        public void onRemoteRttRequest(String callId, Session.Info sessionInfo)
                throws RemoteException {
            Log.startSession(sessionInfo, "CSW.oRRR", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.onRemoteRttRequest();
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void onPhoneAccountChanged(String callId, PhoneAccountHandle pHandle,
                Session.Info sessionInfo) throws RemoteException {
            // Check that the Calling Package matches PhoneAccountHandle's Component Package
            if (pHandle != null) {
                mAppOpsManager.checkPackage(Binder.getCallingUid(),
                        pHandle.getComponentName().getPackageName());
            }
            Log.startSession(sessionInfo, "CSW.oPAC", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setTargetPhoneAccount(pHandle);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void onConnectionServiceFocusReleased(Session.Info sessionInfo)
                throws RemoteException {
            Log.startSession(sessionInfo, "CSW.oCSFR", mPackageAbbreviation);
            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    mConnSvrFocusListener.onConnectionServiceReleased(
                            ConnectionServiceWrapper.this);
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setConferenceState(String callId, boolean isConference,
                Session.Info sessionInfo) throws RemoteException {
            Log.startSession(sessionInfo, "CSW.sCS", mPackageAbbreviation);

            if (mContext.checkCallingOrSelfPermission(MODIFY_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(this, "setConferenceState from caller without permission.");
                Log.endSession();
                return;
            }

            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setConferenceState(isConference);
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void setCallDirection(String callId, int direction, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.sCD", mPackageAbbreviation);

            if (mContext.checkCallingOrSelfPermission(MODIFY_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.w(this, "setCallDirection from caller without permission.");
                Log.endSession();
                return;
            }

            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("setCallDirection %s %d", callId, direction);
                    Call call = mCallIdMapper.getCall(callId);
                    if (call != null) {
                        call.setCallDirection(Call.getRemappedCallDirection(direction));
                    }
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }

        @Override
        public void queryLocation(String callId, long timeoutMillis, String provider,
                ResultReceiver callback, Session.Info sessionInfo) {
            Log.startSession(sessionInfo, "CSW.qL", mPackageAbbreviation);

            TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
            if (telecomManager == null || !telecomManager.getSimCallManager().getComponentName()
                    .equals(getComponentName())) {
                callback.send(0 /* isSuccess */,
                        getQueryLocationErrorResult(QueryLocationException.ERROR_NOT_PERMITTED));
                Log.endSession();
                return;
            }

            String opPackageName = mContext.getOpPackageName();
            int packageUid = -1;
            try {
                packageUid = mContext.getPackageManager().getPackageUid(opPackageName,
                        PackageManager.PackageInfoFlags.of(0));
            } catch (PackageManager.NameNotFoundException e) {
                // packageUid is -1
            }

            try {
                mAppOpsManager.noteProxyOp(
                        AppOpsManager.OPSTR_FINE_LOCATION,
                        opPackageName,
                        packageUid,
                        null,
                        null);
            } catch (SecurityException e) {
                Log.e(ConnectionServiceWrapper.this, e, "");
            }

            if (!callingUidMatchesPackageManagerRecords(getComponentName().getPackageName())) {
                throw new SecurityException(String.format("queryCurrentLocation: "
                                + "uid mismatch found : callingPackageName=[%s], callingUid=[%d]",
                        getComponentName().getPackageName(), Binder.getCallingUid()));
            }

            Call call = mCallIdMapper.getCall(callId);
            if (call == null || !call.isEmergencyCall()) {
                callback.send(0 /* isSuccess */,
                        getQueryLocationErrorResult(QueryLocationException
                                .ERROR_NOT_ALLOWED_FOR_NON_EMERGENCY_CONNECTIONS));
                Log.endSession();
                return;
            }

            long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    logIncoming("queryLocation %s %d", callId, timeoutMillis);
                    ConnectionServiceWrapper.this.queryCurrentLocation(timeoutMillis, provider,
                            callback);
                }
            } catch (Throwable t) {
                Log.e(ConnectionServiceWrapper.this, t, "");
                throw t;
            } finally {
                Binder.restoreCallingIdentity(token);
                Log.endSession();
            }
        }
    }

    private final Adapter mAdapter = new Adapter();
    private final CallIdMapper mCallIdMapper = new CallIdMapper(Call::getConnectionId);
    private final Map<String, CreateConnectionResponse> mPendingResponses = new HashMap<>();

    private Binder2 mBinder = new Binder2();
    private IConnectionService mServiceInterface;
    private final ConnectionServiceRepository mConnectionServiceRepository;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final CallsManager mCallsManager;
    private final AppOpsManager mAppOpsManager;
    private final Context mContext;

    private ConnectionServiceFocusManager.ConnectionServiceFocusListener mConnSvrFocusListener;

    /**
     * Creates a connection service.
     *
     * @param componentName The component name of the service with which to bind.
     * @param connectionServiceRepository Connection service repository.
     * @param phoneAccountRegistrar Phone account registrar
     * @param callsManager Calls manager
     * @param context The context.
     * @param userHandle The {@link UserHandle} to use when binding.
     */
    @VisibleForTesting
    public ConnectionServiceWrapper(
            ComponentName componentName,
            ConnectionServiceRepository connectionServiceRepository,
            PhoneAccountRegistrar phoneAccountRegistrar,
            CallsManager callsManager,
            Context context,
            TelecomSystem.SyncRoot lock,
            UserHandle userHandle,
            FeatureFlags featureFlags) {
        super(ConnectionService.SERVICE_INTERFACE, componentName, context, lock, userHandle,
                featureFlags);
        mConnectionServiceRepository = connectionServiceRepository;
        phoneAccountRegistrar.addListener(new PhoneAccountRegistrar.Listener() {
            // TODO -- Upon changes to PhoneAccountRegistrar, need to re-wire connections
            // To do this, we must proxy remote ConnectionService objects
        });
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mCallsManager = callsManager;
        mAppOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mContext = context;
    }

    /** See {@link IConnectionService#addConnectionServiceAdapter}. */
    private void addConnectionServiceAdapter(IConnectionServiceAdapter adapter) {
        if (isServiceValid("addConnectionServiceAdapter")) {
            try {
                logOutgoing("addConnectionServiceAdapter %s", adapter);
                mServiceInterface.addConnectionServiceAdapter(adapter, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    /** See {@link IConnectionService#removeConnectionServiceAdapter}. */
    private void removeConnectionServiceAdapter(IConnectionServiceAdapter adapter) {
        if (isServiceValid("removeConnectionServiceAdapter")) {
            try {
                logOutgoing("removeConnectionServiceAdapter %s", adapter);
                mServiceInterface.removeConnectionServiceAdapter(adapter, Log.getExternalSession());
            } catch (RemoteException e) {
            }
        }
    }

    @VisibleForTesting
    public CellIdentity getLastKnownCellIdentity() {
        TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);
        if (telephonyManager != null) {
            try {
                CellIdentity lastKnownCellIdentity = telephonyManager.getLastKnownCellIdentity();
                mAppOpsManager.noteOp(AppOpsManager.OP_FINE_LOCATION,
                        mContext.getPackageManager().getPackageUid(
                                getComponentName().getPackageName(), 0),
                        getComponentName().getPackageName());
                return lastKnownCellIdentity;
            } catch (UnsupportedOperationException ignored) {
                Log.w(this, "getLastKnownCellIdentity - no telephony on this device");
            } catch (PackageManager.NameNotFoundException nameNotFoundException) {
                Log.e(this, nameNotFoundException, "could not find the package -- %s",
                        getComponentName().getPackageName());
            }
        }
        return null;
    }

    @VisibleForTesting
    @SuppressWarnings("FutureReturnValueIgnored")
    public void queryCurrentLocation(long timeoutMillis, String provider, ResultReceiver callback) {

        if (mQueryLocationFuture != null && !mQueryLocationFuture.isDone()) {
            callback.send(0 /* isSuccess */,
                    getQueryLocationErrorResult(
                            QueryLocationException.ERROR_PREVIOUS_REQUEST_EXISTS));
            return;
        }

        LocationManager locationManager = (LocationManager) mContext.createAttributionContext(
                ConnectionServiceWrapper.class.getSimpleName()).getSystemService(
                Context.LOCATION_SERVICE);

        if (locationManager == null) {
            callback.send(0 /* isSuccess */,
                    getQueryLocationErrorResult(QueryLocationException.ERROR_SERVICE_UNAVAILABLE));
        }

        mQueryLocationFuture = new CompletableFuture<Pair<Integer, Location>>()
                .completeOnTimeout(
                        Pair.create(QueryLocationException.ERROR_REQUEST_TIME_OUT, null),
                        timeoutMillis, TimeUnit.MILLISECONDS);

        mOngoingQueryLocationRequest = new CancellationSignal();
        locationManager.getCurrentLocation(
                provider,
                new LocationRequest.Builder(0)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setLocationSettingsIgnored(true)
                        .build(),
                mOngoingQueryLocationRequest,
                mQueryLocationExecutor,
                (location) -> mQueryLocationFuture.complete(Pair.create(null, location)));

        mQueryLocationFuture.whenComplete((result, e) -> {
            if (e != null) {
                callback.send(0,
                        getQueryLocationErrorResult(QueryLocationException.ERROR_UNSPECIFIED));
            }
            //make sure we don't pass mock locations diretly, always reset() mock locations
            if (result.second != null) {
                if(result.second.isMock()) {
                    result.second.reset();
                }
                callback.send(1, getQueryLocationResult(result.second));
            } else {
                callback.send(0, getQueryLocationErrorResult(result.first));
            }

            if (mOngoingQueryLocationRequest != null) {
                mOngoingQueryLocationRequest.cancel();
                mOngoingQueryLocationRequest = null;
            }

            if (mQueryLocationFuture != null) {
                mQueryLocationFuture = null;
            }
        });
    }

    private Bundle getQueryLocationResult(Location location) {
        Bundle extras = new Bundle();
        extras.putParcelable(Connection.EXTRA_KEY_QUERY_LOCATION, location);
        return extras;
    }

    private Bundle getQueryLocationErrorResult(int result) {
        String message;

        switch (result) {
            case QueryLocationException.ERROR_REQUEST_TIME_OUT:
                message = "The operation was not completed on time";
                break;
            case QueryLocationException.ERROR_PREVIOUS_REQUEST_EXISTS:
                message = "The operation was rejected due to a previous request exists";
                break;
            case QueryLocationException.ERROR_NOT_PERMITTED:
                message = "The operation is not permitted";
                break;
            case QueryLocationException.ERROR_NOT_ALLOWED_FOR_NON_EMERGENCY_CONNECTIONS:
                message = "Non-emergency call connection are not allowed";
                break;
            case QueryLocationException.ERROR_SERVICE_UNAVAILABLE:
                message = "The operation has failed due to service is not available";
                break;
            default:
                message = "The operation has failed due to an unknown or unspecified error";
        }

        QueryLocationException exception = new QueryLocationException(message, result);
        Bundle extras = new Bundle();
        extras.putParcelable(QueryLocationException.QUERY_LOCATION_ERROR, exception);
        return extras;
    }

    /**
     * helper method that compares the binder_uid to what the packageManager_uid reports for the
     * passed in packageName.
     *
     * returns true if the binder_uid matches the packageManager_uid records
     */
    private boolean callingUidMatchesPackageManagerRecords(String packageName) {
        int packageUid = -1;
        int callingUid = Binder.getCallingUid();

        PackageManager pm;
        try{
            pm = mContext.createContextAsUser(
                    UserHandle.getUserHandleForUid(callingUid), 0).getPackageManager();
        }
        catch (Exception e){
            Log.i(this, "callingUidMatchesPackageManagerRecords:"
                    + " createContextAsUser hit exception=[%s]", e.toString());
            return false;
        }

        if (pm != null) {
            try {
                packageUid = pm.getPackageUid(packageName, PackageManager.PackageInfoFlags.of(0));
            } catch (PackageManager.NameNotFoundException e) {
                // packageUid is -1.
            }
        }

        if (packageUid != callingUid) {
            Log.i(this, "callingUidMatchesPackageManagerRecords: uid mismatch found for "
                    + "packageName=[%s]. packageManager reports packageUid=[%d] but "
                    + "binder reports callingUid=[%d]", packageName, packageUid, callingUid);
        }

        return packageUid == callingUid;
    }

    /**
     * Creates a conference for a new outgoing call or attach to an existing incoming call.
     */
    public void createConference(final Call call, final CreateConnectionResponse response) {
        Log.d(this, "createConference(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                String callId = mCallIdMapper.getCallId(call);
                mPendingResponses.put(callId, response);

                Bundle extras = call.getIntentExtras();
                if (extras == null) {
                    extras = new Bundle();
                }
                extras.putString(Connection.EXTRA_ORIGINAL_CONNECTION_ID, callId);

                Log.addEvent(call, LogUtils.Events.START_CONFERENCE,
                        Log.piiHandle(call.getHandle()));

                ConnectionRequest connectionRequest = new ConnectionRequest.Builder()
                        .setAccountHandle(call.getTargetPhoneAccount())
                        .setAddress(call.getHandle())
                        .setExtras(extras)
                        .setVideoState(call.getVideoState())
                        .setTelecomCallId(callId)
                        // For self-managed incoming calls, if there is another ongoing call Telecom
                        // is responsible for showing a UI to ask the user if they'd like to answer
                        // this new incoming call.
                        .setShouldShowIncomingCallUi(
                                !mCallsManager.shouldShowSystemIncomingCallUi(call))
                        .setRttPipeFromInCall(call.getInCallToCsRttPipeForCs())
                        .setRttPipeToInCall(call.getCsToInCallRttPipeForCs())
                        .setParticipants(call.getParticipants())
                        .setIsAdhocConferenceCall(call.isAdhocConferenceCall())
                        .build();
                Runnable r = new Runnable("CSW.cC", mLock) {
                    @Override
                    public void loggedRun() {
                        if (!call.isCreateConnectionComplete()) {
                            Log.e(this, new Exception(),
                                    "Conference %s creation timeout",
                                    getComponentName());
                            Log.addEvent(call, LogUtils.Events.CREATE_CONFERENCE_TIMEOUT,
                                    Log.piiHandle(call.getHandle()) + " via:" +
                                            getComponentName().getPackageName());
                            mAnomalyReporter.reportAnomaly(
                                    CREATE_CONFERENCE_TIMEOUT_ERROR_UUID,
                                    CREATE_CONFERENCE_TIMEOUT_ERROR_MSG);
                            response.handleCreateConferenceFailure(
                                    new DisconnectCause(DisconnectCause.ERROR));
                        }
                    }
                };
                if (mScheduledExecutor != null && !mScheduledExecutor.isShutdown()) {
                    try {
                        // Post cleanup to the executor service and cache the future,
                        // so we can cancel it if needed.
                        ScheduledFuture<?> future = mScheduledExecutor.schedule(
                                r.getRunnableToCancel(),SERVICE_BINDING_TIMEOUT,
                                TimeUnit.MILLISECONDS);
                        mScheduledFutureMap.put(call, future);
                    } catch (RejectedExecutionException e) {
                        Log.e(this, e, "createConference: mScheduledExecutor was "
                                + "already shutdown");
                        mAnomalyReporter.reportAnomaly(
                                EXECUTOR_REJECTED_EXECUTION_ERROR_UUID,
                                EXECUTOR_REJECTED_EXECUTION_ERROR_MSG);
                    }
                } else {
                    Log.w(this, "createConference: Scheduled executor is null or shutdown");
                    mAnomalyReporter.reportAnomaly(
                        NULL_SCHEDULED_EXECUTOR_ERROR_UUID,
                        NULL_SCHEDULED_EXECUTOR_ERROR_MSG);
                }
                try {
                    mServiceInterface.createConference(
                            call.getConnectionManagerPhoneAccount(),
                            callId,
                            connectionRequest,
                            call.shouldAttachToExistingConnection(),
                            call.isUnknown(),
                            Log.getExternalSession(TELECOM_ABBREVIATION));
                } catch (RemoteException e) {
                    Log.e(this, e, "Failure to createConference -- %s", getComponentName());
                    if (mFlags.dontTimeoutDestroyedCalls()) {
                        maybeRemoveCleanupFuture(call);
                    }
                    mPendingResponses.remove(callId).handleCreateConferenceFailure(
                            new DisconnectCause(DisconnectCause.ERROR, e.toString()));
                }
            }

            @Override
            public void onFailure() {
                Log.e(this, new Exception(), "Failure to conference %s", getComponentName());
                response.handleCreateConferenceFailure(new DisconnectCause(DisconnectCause.ERROR));
            }
        };

        mBinder.bind(callback, call);

    }

    /**
     * Creates a new connection for a new outgoing call or to attach to an existing incoming call.
     */
    @VisibleForTesting
    public void createConnection(final Call call, final CreateConnectionResponse response) {
        Log.i(this, "createConnection(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                String callId = mCallIdMapper.getCallId(call);
                if (callId == null) {
                    Log.i(ConnectionServiceWrapper.this, "Call not present"
                            + " in call id mapper, maybe it was aborted before the bind"
                            + " completed successfully?");
                    if (mFlags.dontTimeoutDestroyedCalls()) {
                        maybeRemoveCleanupFuture(call);
                    }
                    response.handleCreateConnectionFailure(
                            new DisconnectCause(DisconnectCause.CANCELED));
                    return;
                }
                mPendingResponses.put(callId, response);

                GatewayInfo gatewayInfo = call.getGatewayInfo();
                Bundle extras = call.getIntentExtras();
                if (gatewayInfo != null && gatewayInfo.getGatewayProviderPackageName() != null &&
                        gatewayInfo.getOriginalAddress() != null) {
                    extras = (Bundle) extras.clone();
                    extras.putString(
                            TelecomManager.GATEWAY_PROVIDER_PACKAGE,
                            gatewayInfo.getGatewayProviderPackageName());
                    extras.putParcelable(
                            TelecomManager.GATEWAY_ORIGINAL_ADDRESS,
                            gatewayInfo.getOriginalAddress());
                }

                if (call.isIncoming() && mCallsManager.getEmergencyCallHelper()
                        .getLastEmergencyCallTimeMillis() > 0) {
                  // Add the last emergency call time to the connection request for incoming calls
                  if (extras == call.getIntentExtras()) {
                    extras = (Bundle) extras.clone();
                  }
                  extras.putLong(android.telecom.Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS,
                      mCallsManager.getEmergencyCallHelper().getLastEmergencyCallTimeMillis());
                }

                // Call is incoming and added because we're handing over from another; tell CS
                // that its expected to handover.
                if (call.isIncoming() && call.getHandoverSourceCall() != null) {
                    extras.putBoolean(TelecomManager.EXTRA_IS_HANDOVER, true);
                    extras.putParcelable(TelecomManager.EXTRA_HANDOVER_FROM_PHONE_ACCOUNT,
                            call.getHandoverSourceCall().getTargetPhoneAccount());
                }

                Log.addEvent(call, LogUtils.Events.START_CONNECTION,
                        Log.piiHandle(call.getHandle()) + " via:" +
                                getComponentName().getPackageName());

                if (call.isEmergencyCall()) {
                    extras.putParcelable(Connection.EXTRA_LAST_KNOWN_CELL_IDENTITY,
                            getLastKnownCellIdentity());
                }

                ConnectionRequest connectionRequest = new ConnectionRequest.Builder()
                        .setAccountHandle(call.getTargetPhoneAccount())
                        .setAddress(call.getHandle())
                        .setExtras(extras)
                        .setVideoState(call.getVideoState())
                        .setTelecomCallId(callId)
                        // For self-managed incoming calls, if there is another ongoing call Telecom
                        // is responsible for showing a UI to ask the user if they'd like to answer
                        // this new incoming call.
                        .setShouldShowIncomingCallUi(
                                !mCallsManager.shouldShowSystemIncomingCallUi(call))
                        .setRttPipeFromInCall(call.getInCallToCsRttPipeForCs())
                        .setRttPipeToInCall(call.getCsToInCallRttPipeForCs())
                        .build();
                Runnable r = new Runnable("CSW.cC", mLock) {
                    @Override
                    public void loggedRun() {
                        if (!call.isCreateConnectionComplete()) {
                            Log.e(this, new Exception(),
                                    "Connection %s creation timeout",
                                    getComponentName());
                            Log.addEvent(call, LogUtils.Events.CREATE_CONNECTION_TIMEOUT,
                                    Log.piiHandle(call.getHandle()) + " via:" +
                                            getComponentName().getPackageName());
                            mAnomalyReporter.reportAnomaly(
                                    CREATE_CONNECTION_TIMEOUT_ERROR_UUID,
                                    CREATE_CONNECTION_TIMEOUT_ERROR_MSG);
                            response.handleCreateConnectionFailure(
                                    new DisconnectCause(DisconnectCause.ERROR));
                        }
                    }
                };
                if (mScheduledExecutor != null && !mScheduledExecutor.isShutdown()) {
                    try {
                        // Post cleanup to the executor service and cache the future,
                        // so we can cancel it if needed.
                        ScheduledFuture<?> future = mScheduledExecutor.schedule(
                                r.getRunnableToCancel(),SERVICE_BINDING_TIMEOUT,
                                TimeUnit.MILLISECONDS);
                        mScheduledFutureMap.put(call, future);
                    } catch (RejectedExecutionException e) {
                        Log.e(this, e, "createConnection: mScheduledExecutor was "
                                + "already shutdown");
                        mAnomalyReporter.reportAnomaly(
                                EXECUTOR_REJECTED_EXECUTION_ERROR_UUID,
                                EXECUTOR_REJECTED_EXECUTION_ERROR_MSG);
                    }
                } else {
                    Log.w(this, "createConnection: Scheduled executor is null or shutdown");
                    mAnomalyReporter.reportAnomaly(
                        NULL_SCHEDULED_EXECUTOR_ERROR_UUID,
                        NULL_SCHEDULED_EXECUTOR_ERROR_MSG);
                }
                try {
                    if (mFlags.cswServiceInterfaceIsNull() && mServiceInterface == null) {
                        if (mFlags.dontTimeoutDestroyedCalls()) {
                            maybeRemoveCleanupFuture(call);
                        }
                        mPendingResponses.remove(callId).handleCreateConnectionFailure(
                                new DisconnectCause(DisconnectCause.ERROR,
                                        "CSW#oCC ServiceInterface is null"));
                    } else {
                        mServiceInterface.createConnection(
                                call.getConnectionManagerPhoneAccount(),
                                callId,
                                connectionRequest,
                                call.shouldAttachToExistingConnection(),
                                call.isUnknown(),
                                Log.getExternalSession(TELECOM_ABBREVIATION));
                    }
                } catch (RemoteException e) {
                    Log.e(this, e, "Failure to createConnection -- %s", getComponentName());
                    if (mFlags.dontTimeoutDestroyedCalls()) {
                        maybeRemoveCleanupFuture(call);
                    }
                    mPendingResponses.remove(callId).handleCreateConnectionFailure(
                            new DisconnectCause(DisconnectCause.ERROR, e.toString()));
                }
            }

            @Override
            public void onFailure() {
                Log.e(this, new Exception(), "Failure to call %s", getComponentName());
                response.handleCreateConnectionFailure(new DisconnectCause(DisconnectCause.ERROR));
            }
        };

        mBinder.bind(callback, call);
    }

    /**
     * Notifies the {@link ConnectionService} associated with a {@link Call} that the request to
     * create a connection has been denied or failed.
     * @param call The call.
     */
    @VisibleForTesting
    public void createConnectionFailed(final Call call) {
        Log.d(this, "createConnectionFailed(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                final String callId = mCallIdMapper.getCallId(call);
                // If still bound, tell the connection service create connection has failed.
                if (callId != null && isServiceValid("createConnectionFailed")) {
                    Log.addEvent(call, LogUtils.Events.CREATE_CONNECTION_FAILED,
                            Log.piiHandle(call.getHandle()));
                    try {
                        logOutgoing("createConnectionFailed %s", callId);
                        mServiceInterface.createConnectionFailed(
                                call.getConnectionManagerPhoneAccount(),
                                callId,
                                new ConnectionRequest(
                                        call.getTargetPhoneAccount(),
                                        call.getHandle(),
                                        call.getIntentExtras(),
                                        call.getVideoState(),
                                        callId,
                                        false),
                                call.isIncoming(),
                                Log.getExternalSession(TELECOM_ABBREVIATION));
                        call.setDisconnectCause(new DisconnectCause(DisconnectCause.CANCELED));
                        call.disconnect();
                    } catch (RemoteException e) {
                    }
                }
            }

            @Override
            public void onFailure() {
                // Binding failed.  Oh no.
                Log.w(this, "onFailure - could not bind to CS for call %s", call.getId());
            }
        };

        mBinder.bind(callback, call);
    }

    /**
     * Notifies the {@link ConnectionService} associated with a {@link Call} that the request to
     * create a conference has been denied or failed.
     * @param call The call.
     */
    void createConferenceFailed(final Call call) {
        Log.d(this, "createConferenceFailed(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                final String callId = mCallIdMapper.getCallId(call);
                // If still bound, tell the connection service create connection has failed.
                if (callId != null && isServiceValid("createConferenceFailed")) {
                    Log.addEvent(call, LogUtils.Events.CREATE_CONFERENCE_FAILED,
                            Log.piiHandle(call.getHandle()));
                    try {
                        logOutgoing("createConferenceFailed %s", callId);
                        mServiceInterface.createConferenceFailed(
                                call.getConnectionManagerPhoneAccount(),
                                callId,
                                new ConnectionRequest(
                                        call.getTargetPhoneAccount(),
                                        call.getHandle(),
                                        call.getIntentExtras(),
                                        call.getVideoState(),
                                        callId,
                                        false),
                                call.isIncoming(),
                                Log.getExternalSession(TELECOM_ABBREVIATION));
                        call.setDisconnectCause(new DisconnectCause(DisconnectCause.CANCELED));
                        call.disconnect();
                    } catch (RemoteException e) {
                    }
                }
            }

            @Override
            public void onFailure() {
                // Binding failed.  Oh no.
                Log.w(this, "onFailure - could not bind to CS for conf call %s", call.getId());
            }
        };

        mBinder.bind(callback, call);
    }


    void handoverFailed(final Call call, final int reason) {
        Log.d(this, "handoverFailed(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                final String callId = mCallIdMapper.getCallId(call);
                // If still bound, tell the connection service create connection has failed.
                if (callId != null && isServiceValid("handoverFailed")) {
                    Log.addEvent(call, LogUtils.Events.HANDOVER_FAILED,
                            Log.piiHandle(call.getHandle()));
                    try {
                        mServiceInterface.handoverFailed(
                                callId,
                                new ConnectionRequest(
                                        call.getTargetPhoneAccount(),
                                        call.getHandle(),
                                        call.getIntentExtras(),
                                        call.getVideoState(),
                                        callId,
                                        false),
                                reason,
                                Log.getExternalSession(TELECOM_ABBREVIATION));
                    } catch (RemoteException e) {
                    }
                }
            }

            @Override
            public void onFailure() {
                // Binding failed.
                Log.w(this, "onFailure - could not bind to CS for call %s",
                        call.getId());
            }
        };

        mBinder.bind(callback, call);
    }

    void handoverComplete(final Call call) {
        Log.d(this, "handoverComplete(%s) via %s.", call, getComponentName());
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                final String callId = mCallIdMapper.getCallId(call);
                // If still bound, tell the connection service create connection has failed.
                if (callId != null && isServiceValid("handoverComplete")) {
                    try {
                        mServiceInterface.handoverComplete(
                                callId,
                                Log.getExternalSession(TELECOM_ABBREVIATION));
                    } catch (RemoteException e) {
                    }
                }
            }

            @Override
            public void onFailure() {
                // Binding failed.
                Log.w(this, "onFailure - could not bind to CS for call %s",
                        call.getId());
            }
        };

        mBinder.bind(callback, call);
    }

    /** @see IConnectionService#abort(String, Session.Info)  */
    void abort(Call call) {
        // Clear out any pending outgoing call data
        final String callId = mCallIdMapper.getCallId(call);

        // If still bound, tell the connection service to abort.
        if (callId != null && isServiceValid("abort")) {
            try {
                logOutgoing("abort %s", callId);
                mServiceInterface.abort(callId, Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }

        removeCall(call, new DisconnectCause(DisconnectCause.LOCAL));
    }

    /** @see IConnectionService#silence(String, Session.Info) */
    void silence(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("silence")) {
            try {
                logOutgoing("silence %s", callId);
                mServiceInterface.silence(callId, Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#hold(String, Session.Info) */
    void hold(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("hold")) {
            try {
                logOutgoing("hold %s", callId);
                mServiceInterface.hold(callId, Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#unhold(String, Session.Info) */
    void unhold(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("unhold")) {
            try {
                logOutgoing("unhold %s", callId);
                mServiceInterface.unhold(callId, Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#onCallAudioStateChanged(String, CallAudioState, Session.Info) */
    @VisibleForTesting
    public void onCallAudioStateChanged(Call activeCall, CallAudioState audioState) {
        final String callId = mCallIdMapper.getCallId(activeCall);
        if (callId != null && isServiceValid("onCallAudioStateChanged")) {
            try {
                logOutgoing("onCallAudioStateChanged %s %s", callId, audioState);
                mServiceInterface.onCallAudioStateChanged(callId, audioState,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#onCallEndpointChanged(String, CallEndpoint, Session.Info) */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Override
    public void onCallEndpointChanged(Call activeCall, CallEndpoint callEndpoint) {
        final String callId = mCallIdMapper.getCallId(activeCall);
        if (callId != null && isServiceValid("onCallEndpointChanged")) {
            try {
                logOutgoing("onCallEndpointChanged %s %s", callId, callEndpoint);
                mServiceInterface.onCallEndpointChanged(callId, callEndpoint,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
                Log.d(this, "Remote exception calling onCallEndpointChanged");
            }
        }
    }

    /** @see IConnectionService#onAvailableCallEndpointsChanged(String, List, Session.Info) */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Override
    public void onAvailableCallEndpointsChanged(Call activeCall,
            Set<CallEndpoint> availableCallEndpoints) {
        final String callId = mCallIdMapper.getCallId(activeCall);
        if (callId != null && isServiceValid("onAvailableCallEndpointsChanged")) {
            try {
                logOutgoing("onAvailableCallEndpointsChanged %s", callId);
                List<CallEndpoint> availableEndpoints = new ArrayList<>(availableCallEndpoints);
                mServiceInterface.onAvailableCallEndpointsChanged(callId, availableEndpoints,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
                Log.d(this,
                        "Remote exception calling onAvailableCallEndpointsChanged");
            }
        }
    }

    @Override
    public void onVideoStateChanged(Call call, int videoState){
        // pass through. ConnectionService does not implement this method.
    }

    /** @see IConnectionService#onMuteStateChanged(String, boolean, Session.Info) */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Override
    public void onMuteStateChanged(Call activeCall, boolean isMuted) {
        final String callId = mCallIdMapper.getCallId(activeCall);
        if (callId != null && isServiceValid("onMuteStateChanged")) {
            try {
                logOutgoing("onMuteStateChanged %s %s", callId, isMuted);
                mServiceInterface.onMuteStateChanged(callId, isMuted,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
                Log.d(this, "Remote exception calling onMuteStateChanged");
            }
        }
    }

    /** @see IConnectionService#onUsingAlternativeUi(String, boolean, Session.Info) */
    @VisibleForTesting
    public void onUsingAlternativeUi(Call activeCall, boolean isUsingAlternativeUi) {
        final String callId = mCallIdMapper.getCallId(activeCall);
        if (callId != null && isServiceValid("onUsingAlternativeUi")) {
            try {
                logOutgoing("onUsingAlternativeUi %s", isUsingAlternativeUi);
                mServiceInterface.onUsingAlternativeUi(callId, isUsingAlternativeUi,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#onTrackedByNonUiService(String, boolean, Session.Info) */
    @VisibleForTesting
    public void onTrackedByNonUiService(Call activeCall, boolean isTracked) {
        final String callId = mCallIdMapper.getCallId(activeCall);
        if (callId != null && isServiceValid("onTrackedByNonUiService")) {
            try {
                logOutgoing("onTrackedByNonUiService %s", isTracked);
                mServiceInterface.onTrackedByNonUiService(callId, isTracked,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#disconnect(String, Session.Info) */
    @VisibleForTesting
    public void disconnect(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("disconnect")) {
            try {
                logOutgoing("disconnect %s", callId);
                mServiceInterface.disconnect(callId, Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#answer(String, Session.Info) */
    void answer(Call call, int videoState) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("answer")) {
            try {
                logOutgoing("answer %s %d", callId, videoState);
                if (VideoProfile.isAudioOnly(videoState)) {
                    mServiceInterface.answer(callId, Log.getExternalSession(TELECOM_ABBREVIATION));
                } else {
                    mServiceInterface.answerVideo(callId, videoState,
                            Log.getExternalSession(TELECOM_ABBREVIATION));
                }
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#deflect(String, Uri , Session.Info) */
    void deflect(Call call, Uri address) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("deflect")) {
            try {
                logOutgoing("deflect %s", callId);
                mServiceInterface.deflect(callId, address,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#reject(String, Session.Info) */
    void reject(Call call, boolean rejectWithMessage, String message) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("reject")) {
            try {
                logOutgoing("reject %s", callId);

                if (rejectWithMessage && call.can(
                        Connection.CAPABILITY_CAN_SEND_RESPONSE_VIA_CONNECTION)) {
                    mServiceInterface.rejectWithMessage(callId, message,
                            Log.getExternalSession(TELECOM_ABBREVIATION));
                } else {
                    mServiceInterface.reject(callId, Log.getExternalSession(TELECOM_ABBREVIATION));
                }
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#reject(String, Session.Info) */
    void rejectWithReason(Call call, @android.telecom.Call.RejectReason int rejectReason) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("rejectReason")) {
            try {
                logOutgoing("rejectReason %s, %d", callId, rejectReason);

                mServiceInterface.rejectWithReason(callId, rejectReason,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#transfer(String, Uri , boolean, Session.Info) */
    void transfer(Call call, Uri number, boolean isConfirmationRequired) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("transfer")) {
            try {
                logOutgoing("transfer %s", callId);
                mServiceInterface.transfer(callId, number, isConfirmationRequired,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#consultativeTransfer(String, String, Session.Info) */
    void transfer(Call call, Call otherCall) {
        final String callId = mCallIdMapper.getCallId(call);
        final String otherCallId = mCallIdMapper.getCallId(otherCall);
        if (callId != null && otherCallId != null && isServiceValid("consultativeTransfer")) {
            try {
                logOutgoing("consultativeTransfer %s", callId);
                mServiceInterface.consultativeTransfer(callId, otherCallId,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#playDtmfTone(String, char, Session.Info) */
    void playDtmfTone(Call call, char digit) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("playDtmfTone")) {
            try {
                logOutgoing("playDtmfTone %s %c", callId, digit);
                mServiceInterface.playDtmfTone(callId, digit,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    /** @see IConnectionService#stopDtmfTone(String, Session.Info) */
    void stopDtmfTone(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("stopDtmfTone")) {
            try {
                logOutgoing("stopDtmfTone %s", callId);
                mServiceInterface.stopDtmfTone(callId,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException e) {
            }
        }
    }

    @VisibleForTesting
    public void addCall(Call call) {
        if (mCallIdMapper.getCallId(call) == null) {
            mCallIdMapper.addCall(call);
        }
    }

    /**
     * Associates newCall with this connection service by replacing callToReplace.
     */
    void replaceCall(Call newCall, Call callToReplace) {
        Preconditions.checkState(callToReplace.getConnectionService() == this);
        mCallIdMapper.replaceCall(newCall, callToReplace);
    }

    void removeCall(Call call) {
        removeCall(call, new DisconnectCause(DisconnectCause.ERROR));
    }

    void removeCall(String callId, DisconnectCause disconnectCause) {
        CreateConnectionResponse response = mPendingResponses.remove(callId);
        if (response != null) {
            response.handleCreateConnectionFailure(disconnectCause);
        }
        if (mFlags.dontTimeoutDestroyedCalls()) {
            maybeRemoveCleanupFuture(mCallIdMapper.getCall(callId));
        }

        mCallIdMapper.removeCall(callId);
    }

    void removeCall(Call call, DisconnectCause disconnectCause) {
        CreateConnectionResponse response = mPendingResponses.remove(mCallIdMapper.getCallId(call));
        if (response != null) {
            response.handleCreateConnectionFailure(disconnectCause);
        }
        if (mFlags.dontTimeoutDestroyedCalls()) {
            maybeRemoveCleanupFuture(call);
        }

        mCallIdMapper.removeCall(call);
    }

    void onPostDialContinue(Call call, boolean proceed) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("onPostDialContinue")) {
            try {
                logOutgoing("onPostDialContinue %s %b", callId, proceed);
                mServiceInterface.onPostDialContinue(callId, proceed,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void conference(final Call call, Call otherCall) {
        final String callId = mCallIdMapper.getCallId(call);
        final String otherCallId = mCallIdMapper.getCallId(otherCall);
        if (callId != null && otherCallId != null && isServiceValid("conference")) {
            try {
                logOutgoing("conference %s %s", callId, otherCallId);
                mServiceInterface.conference(callId, otherCallId,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void splitFromConference(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("splitFromConference")) {
            try {
                logOutgoing("splitFromConference %s", callId);
                mServiceInterface.splitFromConference(callId,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void mergeConference(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("mergeConference")) {
            try {
                logOutgoing("mergeConference %s", callId);
                mServiceInterface.mergeConference(callId,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void swapConference(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("swapConference")) {
            try {
                logOutgoing("swapConference %s", callId);
                mServiceInterface.swapConference(callId,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void addConferenceParticipants(Call call, List<Uri> participants) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("addConferenceParticipants")) {
            try {
                logOutgoing("addConferenceParticipants %s", callId);
                mServiceInterface.addConferenceParticipants(callId, participants,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    @VisibleForTesting
    public void pullExternalCall(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("pullExternalCall")) {
            try {
                logOutgoing("pullExternalCall %s", callId);
                mServiceInterface.pullExternalCall(callId,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    @Override
    public void sendCallEvent(Call call, String event, Bundle extras) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("sendCallEvent")) {
            try {
                logOutgoing("sendCallEvent %s %s", callId, event);
                mServiceInterface.sendCallEvent(callId, event, extras,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void onCallFilteringCompleted(Call call,
            Connection.CallFilteringCompletionInfo completionInfo) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("onCallFilteringCompleted")) {
            try {
                logOutgoing("onCallFilteringCompleted %s", completionInfo);
                int contactsPermission = mContext.getPackageManager()
                        .checkPermission(Manifest.permission.READ_CONTACTS,
                                getComponentName().getPackageName());
                if (contactsPermission == PackageManager.PERMISSION_GRANTED) {
                    mServiceInterface.onCallFilteringCompleted(callId, completionInfo,
                            Log.getExternalSession(TELECOM_ABBREVIATION));
                } else {
                    logOutgoing("Skipping call filtering complete message for %s due"
                            + " to lack of READ_CONTACTS", getComponentName().getPackageName());
                }
            } catch (RemoteException e) {
                Log.e(this, e, "Remote exception calling onCallFilteringCompleted");
            }
        }
    }

    void onExtrasChanged(Call call, Bundle extras) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("onExtrasChanged")) {
            try {
                logOutgoing("onExtrasChanged %s %s", callId, extras);
                mServiceInterface.onExtrasChanged(callId, extras,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void startRtt(Call call, ParcelFileDescriptor fromInCall, ParcelFileDescriptor toInCall) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("startRtt")) {
            try {
                logOutgoing("startRtt: %s %s %s", callId, fromInCall, toInCall);
                mServiceInterface.startRtt(callId, fromInCall, toInCall,
                        Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void stopRtt(Call call) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("stopRtt")) {
            try {
                logOutgoing("stopRtt: %s", callId);
                mServiceInterface.stopRtt(callId, Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    void respondToRttRequest(
            Call call, ParcelFileDescriptor fromInCall, ParcelFileDescriptor toInCall) {
        final String callId = mCallIdMapper.getCallId(call);
        if (callId != null && isServiceValid("respondToRttRequest")) {
            try {
                logOutgoing("respondToRttRequest: %s %s %s", callId, fromInCall, toInCall);
                mServiceInterface.respondToRttUpgradeRequest(
                        callId, fromInCall, toInCall, Log.getExternalSession(TELECOM_ABBREVIATION));
            } catch (RemoteException ignored) {
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void setServiceInterface(IBinder binder) {
        mServiceInterface = IConnectionService.Stub.asInterface(binder);
        Log.v(this, "Adding Connection Service Adapter.");
        addConnectionServiceAdapter(mAdapter);
    }

    /** {@inheritDoc} */
    @Override
    protected void removeServiceInterface() {
        Log.v(this, "Removing Connection Service Adapter.");
        if (mServiceInterface == null) {
            // In some cases, we may receive multiple calls to
            // remoteServiceInterface, such as when the remote process crashes
            // (onBinderDied & onServiceDisconnected)
            Log.w(this, "removeServiceInterface: mServiceInterface is null");
            return;
        }
        removeConnectionServiceAdapter(mAdapter);
        // We have lost our service connection. Notify the world that this service is done.
        // We must notify the adapter before CallsManager. The adapter will force any pending
        // outgoing calls to try the next service. This needs to happen before CallsManager
        // tries to clean up any calls still associated with this service.
        handleConnectionServiceDeath();
        mCallsManager.handleConnectionServiceDeath(this);
        mServiceInterface = null;
        if (mScheduledExecutor != null) {
            mScheduledExecutor.shutdown();
            mScheduledExecutor = null;
        }
    }

    @Override
    public void connectionServiceFocusLost() {
        // Immediately response to the Telecom that it has released the call resources.
        // TODO(mpq): Change back to the default implementation once b/69651192 done.
        if (mConnSvrFocusListener != null) {
            mConnSvrFocusListener.onConnectionServiceReleased(ConnectionServiceWrapper.this);
        }
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (!isServiceValid("connectionServiceFocusLost")) return;
                try {
                    mServiceInterface.connectionServiceFocusLost(
                            Log.getExternalSession(TELECOM_ABBREVIATION));
                } catch (RemoteException ignored) {
                    Log.d(this, "failed to inform the focus lost event");
                }
            }

            @Override
            public void onFailure() {}
        };
        mBinder.bind(callback, null /* null call */);
    }

    @Override
    public void connectionServiceFocusGained() {
        BindCallback callback = new BindCallback() {
            @Override
            public void onSuccess() {
                if (!isServiceValid("connectionServiceFocusGained")) return;
                try {
                    mServiceInterface.connectionServiceFocusGained(
                            Log.getExternalSession(TELECOM_ABBREVIATION));
                } catch (RemoteException ignored) {
                    Log.d(this, "failed to inform the focus gained event");
                }
            }

            @Override
            public void onFailure() {}
        };
        mBinder.bind(callback, null /* null call */);
    }

    @Override
    public void setConnectionServiceFocusListener(
            ConnectionServiceFocusManager.ConnectionServiceFocusListener listener) {
        mConnSvrFocusListener = listener;
    }

    private void handleCreateConnectionComplete(
            String callId,
            ConnectionRequest request,
            ParcelableConnection connection) {
        // TODO: Note we are not using parameter "request", which is a side effect of our tacit
        // assumption that we have at most one outgoing connection attempt per ConnectionService.
        // This may not continue to be the case.
        if (connection.getState() == Connection.STATE_DISCONNECTED) {
            // A connection that begins in the DISCONNECTED state is an indication of
            // failure to connect; we handle all failures uniformly
            Call foundCall = mCallIdMapper.getCall(callId);

            if (foundCall != null) {
                if (connection.getConnectTimeMillis() != 0) {
                    foundCall.setConnectTimeMillis(connection.getConnectTimeMillis());
                }

                // The post-dial digits are created when the call is first created.  Normally
                // the ConnectionService is responsible for stripping them from the address, but
                // since a failed connection will not have done this, we could end up with duplicate
                // post-dial digits.
                foundCall.clearPostDialDigits();
            }
            removeCall(callId, connection.getDisconnectCause());
        } else {
            // Successful connection
            if (mPendingResponses.containsKey(callId)) {
                mPendingResponses.remove(callId)
                        .handleCreateConnectionSuccess(mCallIdMapper, connection);
            }
        }
    }

    private void handleCreateConferenceComplete(
            String callId,
            ConnectionRequest request,
            ParcelableConference conference) {
        // TODO: Note we are not using parameter "request", which is a side effect of our tacit
        // assumption that we have at most one outgoing conference attempt per ConnectionService.
        // This may not continue to be the case.
        if (conference.getState() == Connection.STATE_DISCONNECTED) {
            // A conference that begins in the DISCONNECTED state is an indication of
            // failure to connect; we handle all failures uniformly
            removeCall(callId, conference.getDisconnectCause());
        } else {
            // Successful connection
            if (mPendingResponses.containsKey(callId)) {
                mPendingResponses.remove(callId)
                        .handleCreateConferenceSuccess(mCallIdMapper, conference);
            }
        }
    }

    /**
     * Called when the associated connection service dies.
     */
    private void handleConnectionServiceDeath() {
        if (!mPendingResponses.isEmpty()) {
            Collection<CreateConnectionResponse> responses = mPendingResponses.values();
            mPendingResponses.clear();
            for (CreateConnectionResponse response : responses) {
                response.handleCreateConnectionFailure(new DisconnectCause(DisconnectCause.ERROR,
                        "CS_DEATH"));
            }
        }
        mCallIdMapper.clear();
        mScheduledFutureMap.clear();

        if (mConnSvrFocusListener != null) {
            mConnSvrFocusListener.onConnectionServiceDeath(this);
        }
    }

    private void logIncoming(String msg, Object... params) {
        // Keep these as debug; the incoming logging is traced on a package level through the
        // session logging.
        Log.d(this, "CS -> TC[" + Log.getPackageAbbreviation(mComponentName) + "]: "
                + msg, params);
    }

    private void logOutgoing(String msg, Object... params) {
        Log.d(this, "TC -> CS[" + Log.getPackageAbbreviation(mComponentName) + "]: "
                + msg, params);
    }

    private void queryRemoteConnectionServices(final UserHandle userHandle,
            final String callingPackage, final RemoteServiceCallback callback) {
        boolean isCallerConnectionManager = false;
        // For each Sim ConnectionService, use its subid to find the correct connection manager for
        // that ConnectionService; return those Sim ConnectionServices which match the connection
        // manager.
        final Set<ConnectionServiceWrapper> simServices = Collections.newSetFromMap(
                new ConcurrentHashMap<ConnectionServiceWrapper, Boolean>(8, 0.9f, 1));
        for (PhoneAccountHandle handle : mPhoneAccountRegistrar.getSimPhoneAccounts(userHandle)) {
            int subId = mPhoneAccountRegistrar.getSubscriptionIdForPhoneAccount(handle);
            PhoneAccountHandle connectionMgrHandle = mPhoneAccountRegistrar.getSimCallManager(subId,
                    userHandle);
            if (connectionMgrHandle == null
                    || !connectionMgrHandle.getComponentName().getPackageName().equals(
                            callingPackage)) {
                Log.v(this, "queryRemoteConnectionServices: callingPackage=%s skipped; "
                                + "doesn't match mgr %s for tfa %s",
                        callingPackage, connectionMgrHandle, handle);
            } else {
                isCallerConnectionManager = true;
            }
            ConnectionServiceWrapper service = mConnectionServiceRepository.getService(
                    handle.getComponentName(), handle.getUserHandle());
            if (service != null && service != this) {
                simServices.add(service);
            } else {
                // This is unexpected, normally PhoneAccounts with CAPABILITY_CALL_PROVIDER are not
                // also CAPABILITY_CONNECTION_MANAGER
                Log.w(this, "call provider also detected as SIM call manager: " + service);
            }
        }

        Log.i(this, "queryRemoteConnectionServices, simServices = %s", simServices);
        // Bail early if the caller isn't the sim connection mgr or no sim connection service
        // other than caller available.
        if (!isCallerConnectionManager || simServices.isEmpty()) {
            Log.d(this, "queryRemoteConnectionServices: not sim call mgr or no simservices.");
            noRemoteServices(callback);
            return;
        }

        final List<ComponentName> simServiceComponentNames = new ArrayList<>();
        final List<IBinder> simServiceBinders = new ArrayList<>();

        for (ConnectionServiceWrapper simService : simServices) {
            final ConnectionServiceWrapper currentSimService = simService;

            currentSimService.mBinder.bind(new BindCallback() {
                @Override
                public void onSuccess() {
                    Log.d(this, "queryRemoteConnectionServices: Adding simService %s",
                            currentSimService.getComponentName());
                    if (currentSimService.mServiceInterface == null) {
                        // The remote ConnectionService died, so do not add it.
                        // We will still perform maybeComplete() and notify the caller with an empty
                        // list of sim services via maybeComplete().
                        Log.w(this, "queryRemoteConnectionServices: simService %s died - Skipping.",
                                currentSimService.getComponentName());
                    } else {
                        simServiceComponentNames.add(currentSimService.getComponentName());
                        simServiceBinders.add(currentSimService.mServiceInterface.asBinder());
                    }
                    maybeComplete();
                }

                @Override
                public void onFailure() {
                    Log.d(this, "queryRemoteConnectionServices: Failed simService %s",
                            currentSimService.getComponentName());
                    // We know maybeComplete() will always be a no-op from now on, so go ahead and
                    // signal failure of the entire request
                    noRemoteServices(callback);
                }

                private void maybeComplete() {
                    if (simServiceComponentNames.size() == simServices.size()) {
                        setRemoteServices(callback, simServiceComponentNames, simServiceBinders);
                    }
                }
            }, null);
        }
    }

    private void setRemoteServices(
            RemoteServiceCallback callback,
            List<ComponentName> componentNames,
            List<IBinder> binders) {
        try {
            callback.onResult(componentNames, binders);
        } catch (RemoteException e) {
            Log.e(this, e, "setRemoteServices: Contacting ConnectionService %s",
                    ConnectionServiceWrapper.this.getComponentName());
        }
    }

    private void noRemoteServices(RemoteServiceCallback callback) {
        setRemoteServices(callback, Collections.EMPTY_LIST, Collections.EMPTY_LIST);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ConnectionServiceWrapper componentName=");
        sb.append(mComponentName);
        sb.append("]");
        return sb.toString();
    }

    @VisibleForTesting
    public void setScheduledExecutorService(ScheduledExecutorService service) {
        mScheduledExecutor = service;
    }

    @VisibleForTesting
    public void setAnomalyReporterAdapter(AnomalyReporterAdapter mAnomalyReporterAdapter){
        mAnomalyReporter = mAnomalyReporterAdapter;
    }

    /**
     * Given a call, unschedule and cancel the cleanup future.
     * @param call the call.
     */
    private void maybeRemoveCleanupFuture(Call call) {
        if (call == null) {
            return;
        }
        ScheduledFuture<?> future = mScheduledFutureMap.remove(call);
        if (future == null) {
            return;
        }
        future.cancel(false /* interrupt */);

    }
}
