/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.telecom;

import android.content.Context;
import android.os.SystemClock;
import android.telecom.Logging.EventManager;
import android.telecom.Logging.EventManager.TimedEventPair;

import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.Map;

/**
 * Temporary location of new Logging class
 */

public class LogUtils {

    private static final String TAG = "Telecom";
    private static final String LOGUTILS_TAG = "LogUtils";

    public static final boolean SYSTRACE_DEBUG = false; /* STOP SHIP if true */
    private static boolean sInitializedLoggerListeners = false; // used to gate re-init of listeners
    private static int sInitializedCounter = 0; /* For testing purposes only */
    private static final Object sLock = new Object(); // Coarse lock for all of LogUtils

    public static class EventTimer {
        private long mLastElapsedMillis;
        private Map<String, Long> mTimings = new HashMap<>();

        public EventTimer() {
            mLastElapsedMillis = SystemClock.elapsedRealtime();
        }

        public void record(String label) {
            long newElapsedMillis = SystemClock.elapsedRealtime();
            mTimings.put(label, newElapsedMillis - mLastElapsedMillis);
            mLastElapsedMillis = newElapsedMillis;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Long> entry : mTimings.entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append(", ");
            }
            return sb.toString();
        }
    }

    public static final class Sessions {
        public static final String ICA_ANSWER_CALL = "ICA.aC";
        public static final String ICA_DEFLECT_CALL = "ICA.defC";
        public static final String ICA_REJECT_CALL = "ICA.rC";
        public static final String ICA_TRANSFER_CALL = "ICA.tC";
        public static final String ICA_CONSULTATIVE_TRANSFER = "ICA.cT";
        public static final String ICA_DISCONNECT_CALL = "ICA.dC";
        public static final String ICA_HOLD_CALL = "ICA.hC";
        public static final String ICA_UNHOLD_CALL = "ICA.uC";
        public static final String ICA_MUTE = "ICA.m";
        public static final String ICA_SET_AUDIO_ROUTE = "ICA.sAR";
        public static final String ICA_ENTER_AUDIO_PROCESSING = "ICA.enBAP";
        public static final String ICA_EXIT_AUDIO_PROCESSING = "ICA.exBAP";
        public static final String ICA_CONFERENCE = "ICA.c";
        public static final String CSW_HANDLE_CREATE_CONNECTION_COMPLETE = "CSW.hCCC";
        public static final String CSW_SET_ACTIVE = "CSW.sA";
        public static final String CSW_SET_RINGING = "CSW.sR";
        public static final String CSW_SET_DIALING = "CSW.sD";
        public static final String CSW_SET_PULLING = "CSW.sP";
        public static final String CSW_SET_DISCONNECTED = "CSW.sDc";
        public static final String CSW_SET_ON_HOLD = "CSW.sOH";
        public static final String CSW_REMOVE_CALL = "CSW.rC";
        public static final String CSW_SET_IS_CONFERENCED = "CSW.sIC";
        public static final String CSW_ADD_CONFERENCE_CALL = "CSW.aCC";
        public static final String CSA_SET_STATE = "CSA.sSS";
    }

    public final static class Events {
        public static final String CREATED = "CREATED";
        public static final String USER_CONFIRMATION = "USER_CONFIRMATION";
        public static final String USER_CONFIRMED = "USER_CONFIRMED";
        public static final String USER_CANCELLED = "USER_CANCELLED";
        public static final String DESTROYED = "DESTROYED";
        public static final String SET_CONNECTING = "SET_CONNECTING";
        public static final String SET_DIALING = "SET_DIALING";
        public static final String SET_PULLING = "SET_PULLING";
        public static final String SET_ACTIVE = "SET_ACTIVE";
        public static final String SET_HOLD = "SET_HOLD";
        public static final String SET_RINGING = "SET_RINGING";
        public static final String SET_ANSWERED = "SET_ANSWERED";
        public static final String SET_DISCONNECTED = "SET_DISCONNECTED";
        public static final String SKIP_CALL_LOG = "SKIP_CALL_LOG";
        public static final String LOG_CALL = "LOG_CALL";
        public static final String SET_DISCONNECTING = "SET_DISCONNECTING";
        public static final String SET_SELECT_PHONE_ACCOUNT = "SET_SELECT_PHONE_ACCOUNT";
        public static final String SET_AUDIO_PROCESSING = "SET_AUDIO_PROCESSING";
        public static final String SET_SIMULATED_RINGING = "SET_SIMULATED_RINGING";
        public static final String REQUEST_RTT = "REQUEST_RTT";
        public static final String RESPOND_TO_RTT_REQUEST = "RESPOND_TO_RTT_REQUEST";
        public static final String SET_RRT_MODE = "SET_RTT_MODE";
        public static final String ON_RTT_FAILED = "ON_RTT_FAILED";
        public static final String ON_RTT_REQUEST = "ON_RTT_REQUEST";
        public static final String REQUEST_HOLD = "REQUEST_HOLD";
        public static final String REQUEST_UNHOLD = "REQUEST_UNHOLD";
        public static final String REQUEST_DISCONNECT = "REQUEST_DISCONNECT";
        public static final String REQUEST_ACCEPT = "REQUEST_ACCEPT";
        public static final String REQUEST_SIMULATED_ACCEPT = "REQUEST_SIMULATED_ACCEPT";
        public static final String REQUEST_PICKUP_FOR_AUDIO_PROCESSING =
                "REQUEST_PICKUP_FOR_AUDIO_PROCESSING";
        public static final String REQUEST_DEFLECT = "REQUEST_DEFLECT";
        public static final String REQUEST_REJECT = "REQUEST_REJECT";
        public static final String REQUEST_TRANSFER = "REQUEST_TRANSFER";
        public static final String REQUEST_CONSULTATIVE_TRANSFER = "REQUEST_CONSULTATIVE_TRANSFER";
        public static final String START_DTMF = "START_DTMF";
        public static final String STOP_DTMF = "STOP_DTMF";
        public static final String START_RINGER = "START_RINGER";
        public static final String STOP_RINGER = "STOP_RINGER";
        public static final String START_VIBRATOR = "START_VIBRATOR";
        public static final String STOP_VIBRATOR = "STOP_VIBRATOR";
        public static final String SKIP_VIBRATION = "SKIP_VIBRATION";
        public static final String SKIP_RINGING = "SKIP_RINGING";
        public static final String START_CALL_WAITING_TONE = "START_CALL_WAITING_TONE";
        public static final String STOP_CALL_WAITING_TONE = "STOP_CALL_WAITING_TONE";
        public static final String START_CONNECTION = "START_CONNECTION";
        public static final String CREATE_CONNECTION_FAILED = "CREATE_CONNECTION_FAILED";
        public static final String CREATE_CONNECTION_TIMEOUT = "CREATE_CONNECTION_TIMEOUT";
        public static final String START_CONFERENCE = "START_CONFERENCE";
        public static final String CREATE_CONFERENCE_FAILED = "CREATE_CONFERENCE_FAILED";
        public static final String CREATE_CONFERENCE_TIMEOUT = "CREATE_CONFERENCE_TIMEOUT";
        public static final String BIND_CS = "BIND_CS";
        public static final String CS_BOUND = "CS_BOUND";
        public static final String CONFERENCE_WITH = "CONF_WITH";
        public static final String SPLIT_FROM_CONFERENCE = "CONF_SPLIT";
        public static final String SWAP = "SWAP";
        public static final String ADD_PARTICIPANT = "ADD_PARTICIPANT";
        public static final String ADD_CHILD = "ADD_CHILD";
        public static final String REMOVE_CHILD = "REMOVE_CHILD";
        public static final String SET_PARENT = "SET_PARENT";
        public static final String CONF_STATE_CHANGED = "CONF_STATE_CHANGED";
        public static final String CONF_CALLS_CHANGED = "CONF_CALLS_CHANGED";
        public static final String CALL_DIRECTION_CHANGED = "CALL_DIRECTION_CHANGED";
        public static final String MUTE = "MUTE";
        public static final String UNMUTE = "UNMUTE";
        public static final String AUDIO_ROUTE = "AUDIO_ROUTE";
        public static final String AUDIO_ROUTE_EARPIECE = "AUDIO_ROUTE_EARPIECE";
        public static final String AUDIO_ROUTE_HEADSET = "AUDIO_ROUTE_HEADSET";
        public static final String AUDIO_ROUTE_BT = "AUDIO_ROUTE_BT";
        public static final String AUDIO_ROUTE_SPEAKER = "AUDIO_ROUTE_SPEAKER";
        public static final String ERROR_LOG = "ERROR";
        public static final String USER_LOG_MARK = "USER_LOG_MARK";
        public static final String SILENCE = "SILENCE";
        public static final String BIND_SCREENING = "BIND_SCREENING";
        public static final String SCREENING_BOUND = "SCREENING_BOUND";
        public static final String SCREENING_SENT = "SCREENING_SENT";
        public static final String SCREENING_SKIPPED = "SCREENING_SKIPPED";
        public static final String CONTROLLER_SCREENING_COMPLETED =
                "CONTROLLER_SCREENING_COMPLETED";
        public static final String SCREENING_COMPLETED = "SCREENING_COMPLETED";
        public static final String CALL_IDENTIFICATION_SET = "CALL_IDENTIFICATION_SET";
        public static final String BLOCK_CHECK_INITIATED = "BLOCK_CHECK_INITIATED";
        public static final String BLOCK_CHECK_FINISHED = "BLOCK_CHECK_FINISHED";
        public static final String DIRECT_TO_VM_INITIATED = "DIRECT_TO_VM_INITIATED";
        public static final String DIRECT_TO_VM_FINISHED = "DIRECT_TO_VM_FINISHED";
        public static final String FILTERING_INITIATED = "FILTERING_INITIATED";
        public static final String DND_PRE_CHECK_INITIATED = "DND_PRE_CHECK_INITIATED";
        public static final String DND_PRE_CHECK_COMPLETED = "DND_PRE_CHECK_COMPLETED";
        public static final String FILTERING_COMPLETED = "FILTERING_COMPLETED";
        public static final String FILTERING_TIMED_OUT = "FILTERING_TIMED_OUT";
        public static final String REMOTELY_HELD = "REMOTELY_HELD";
        public static final String REMOTELY_UNHELD = "REMOTELY_UNHELD";
        public static final String REQUEST_PULL = "PULL";
        public static final String INFO = "INFO";
        public static final String VIDEO_STATE_CHANGED = "VIDEO_STATE_CHANGED";
        public static final String RECEIVE_VIDEO_REQUEST = "RECEIVE_VIDEO_REQUEST";
        public static final String RECEIVE_VIDEO_RESPONSE = "RECEIVE_VIDEO_RESPONSE";
        public static final String SEND_VIDEO_REQUEST = "SEND_VIDEO_REQUEST";
        public static final String SEND_VIDEO_RESPONSE = "SEND_VIDEO_RESPONSE";
        public static final String IS_EXTERNAL = "IS_EXTERNAL";
        public static final String PROPERTY_CHANGE = "PROPERTY_CHANGE";
        public static final String CAPABILITY_CHANGE = "CAPABILITY_CHANGE";
        public static final String CONNECTION_EVENT = "CONNECTION_EVENT";
        public static final String CALL_EVENT = "CALL_EVENT";
        public static final String HANDOVER_REQUEST = "HANDOVER_REQUEST";
        public static final String START_HANDOVER = "START_HANDOVER";
        public static final String ACCEPT_HANDOVER = "ACCEPT_HANDOVER";
        public static final String HANDOVER_COMPLETE = "HANDOVER_COMPLETE";
        public static final String HANDOVER_FAILED = "HANDOVER_FAILED";
        public static final String START_RINBACK = "START_RINGBACK";
        public static final String STOP_RINGBACK = "STOP_RINGBACK";
        public static final String REDIRECTION_BOUND_USER = "REDIRECTION_BOUND_USER";
        public static final String REDIRECTION_BOUND_CARRIER = "REDIRECTION_BOUND_CARRIER";
        public static final String REDIRECTION_SENT_USER = "REDIRECTION_SENT_USER";
        public static final String REDIRECTION_SENT_CARRIER = "REDIRECTION_SENT_CARRIER";
        public static final String REDIRECTION_COMPLETED_USER = "REDIRECTION_COMPLETED_USER";
        public static final String REDIRECTION_COMPLETED_CARRIER = "REDIRECTION_COMPLETED_CARRIER";
        public static final String REDIRECTION_TIMED_OUT_USER = "REDIRECTION_TIMED_OUT_USER";
        public static final String REDIRECTION_TIMED_OUT_CARRIER = "REDIRECTION_TIMED_OUT_CARRIER";
        public static final String REDIRECTION_USER_CONFIRMATION = "REDIRECTION_USER_CONFIRMATION";
        public static final String REDIRECTION_USER_CONFIRMED = "REDIRECTION_USER_CONFIRMED";
        public static final String REDIRECTION_USER_CANCELLED = "REDIRECTION_USER_CANCELLED";
        public static final String BT_QUALITY_REPORT = "BT_QUALITY_REPORT";
        public static final String SET_DISCONNECTED_ORIG = "SET_DISCONNECTED_ORIG";
        public static final String OVERRIDE_DISCONNECT_MESSAGE = "OVERRIDE_DISCONNECT_MSG";
        public static final String CALL_DIAGNOSTIC_SERVICE_TIMEOUT =
                "CALL_DIAGNOSTIC_SERVICE_TIMEOUT";
        public static final String VERSTAT_CHANGED = "VERSTAT_CHANGED";
        public static final String SET_VOIP_MODE = "SET_VOIP_MODE";
        public static final String STATE_TIMEOUT = "STATE_TIMEOUT";
        public static final String ICS_EXTRAS_CHANGED = "ICS_EXTRAS_CHANGED";
        public static final String FLASH_NOTIFICATION_START = "FLASH_NOTIFICATION_START";
        public static final String FLASH_NOTIFICATION_STOP = "FLASH_NOTIFICATION_STOP";
        public static final String GAINED_FGS_DELEGATION = "GAINED_FGS_DELEGATION";
        public static final String ALREADY_HAS_FGS_DELEGATION = "ALREADY_HAS_FGS_DELEGATION";
        public static final String MAINTAINING_FGS_DELEGATION = "MAINTAINING_FGS_DELEGATION";
        public static final String GAIN_FGS_DELEGATION_FAILED = "GAIN_FGS_DELEGATION_FAILED";
        public static final String LOST_FGS_DELEGATION = "LOST_FGS_DELEGATION";
        public static final String START_STREAMING = "START_STREAMING";
        public static final String STOP_STREAMING = "STOP_STREAMING";
        public static final String AUDIO_ATTR = "AUDIO_ATTR";
        public static final String ANSWER_DROPS_FG = "ANSWER_DROPS_FG";

        public static class Timings {
            public static final String ACCEPT_TIMING = "accept";
            public static final String REJECT_TIMING = "reject";
            public static final String DISCONNECT_TIMING = "disconnect";
            public static final String HOLD_TIMING = "hold";
            public static final String UNHOLD_TIMING = "unhold";
            public static final String OUTGOING_TIME_TO_DIALING_TIMING = "outgoing_time_to_dialing";
            public static final String BIND_CS_TIMING = "bind_cs";
            public static final String SCREENING_COMPLETED_TIMING = "screening_completed";
            public static final String DIRECT_TO_VM_FINISHED_TIMING = "direct_to_vm_finished";
            public static final String BLOCK_CHECK_FINISHED_TIMING = "block_check_finished";
            public static final String FILTERING_COMPLETED_TIMING = "filtering_completed";
            public static final String DND_PRE_CHECK_COMPLETED_TIMING = "dnd_pre_check_completed";
            public static final String FILTERING_TIMED_OUT_TIMING = "filtering_timed_out";
            public static final String START_CONNECTION_TO_REQUEST_DISCONNECT_TIMING =
                    "start_connection_to_request_disconnect";

            private static final TimedEventPair[] sTimedEvents = {
                    new TimedEventPair(REQUEST_ACCEPT, SET_ACTIVE, ACCEPT_TIMING),
                    new TimedEventPair(REQUEST_REJECT, SET_DISCONNECTED, REJECT_TIMING),
                    new TimedEventPair(REQUEST_DISCONNECT, SET_DISCONNECTED, DISCONNECT_TIMING),
                    new TimedEventPair(REQUEST_HOLD, SET_HOLD, HOLD_TIMING),
                    new TimedEventPair(REQUEST_UNHOLD, SET_ACTIVE, UNHOLD_TIMING),
                    new TimedEventPair(START_CONNECTION, SET_DIALING,
                            OUTGOING_TIME_TO_DIALING_TIMING),
                    new TimedEventPair(BIND_CS, CS_BOUND, BIND_CS_TIMING),
                    new TimedEventPair(SCREENING_SENT, SCREENING_COMPLETED,
                            SCREENING_COMPLETED_TIMING),
                    new TimedEventPair(DIRECT_TO_VM_INITIATED, DIRECT_TO_VM_FINISHED,
                            DIRECT_TO_VM_FINISHED_TIMING),
                    new TimedEventPair(BLOCK_CHECK_INITIATED, BLOCK_CHECK_FINISHED,
                            BLOCK_CHECK_FINISHED_TIMING),
                    new TimedEventPair(FILTERING_INITIATED, FILTERING_COMPLETED,
                            FILTERING_COMPLETED_TIMING),
                    new TimedEventPair(DND_PRE_CHECK_INITIATED, DND_PRE_CHECK_COMPLETED,
                            DND_PRE_CHECK_COMPLETED_TIMING),
                    new TimedEventPair(FILTERING_INITIATED, FILTERING_TIMED_OUT,
                            FILTERING_TIMED_OUT_TIMING, 6000L),
                    new TimedEventPair(START_CONNECTION, REQUEST_DISCONNECT,
                            START_CONNECTION_TO_REQUEST_DISCONNECT_TIMING),
            };
        }
    }

    private static void eventRecordAdded(EventManager.EventRecord eventRecord) {
        // Only Calls will be added as event records in this case
        EventManager.Loggable recordEntry = eventRecord.getRecordEntry();
        if (recordEntry instanceof Call) {
            Call callRecordEntry = (Call) recordEntry;
            Analytics.CallInfo callInfo = callRecordEntry.getAnalytics();
            if(callInfo != null) {
                callInfo.setCallEvents(eventRecord);
            } else {
                if(!android.telecom.Log.isUnitTestingEnabled()) {
                    android.telecom.Log.w(LOGUTILS_TAG, "Could not get Analytics CallInfo.");
                }
            }
        } else {
            android.telecom.Log.w(LOGUTILS_TAG, "Non-Call EventRecord Added.");
        }
    }

    public static void initLogging(Context context) {
        android.telecom.Log.d(LOGUTILS_TAG, "initLogging: attempting to acquire LogUtils sLock");
        synchronized (sLock) {
            android.telecom.Log.d(LOGUTILS_TAG, "initLogging: grabbed LogUtils sLock");
            if (!sInitializedLoggerListeners) {
                android.telecom.Log.d(LOGUTILS_TAG,
                        "initLogging: called for first time. Registering the EventListener &"
                                + " SessionListener.");

                android.telecom.Log.setTag(TAG);
                android.telecom.Log.setSessionContext(context);
                for (EventManager.TimedEventPair p : Events.Timings.sTimedEvents) {
                    android.telecom.Log.addRequestResponsePair(p);
                }
                android.telecom.Log.registerEventListener(LogUtils::eventRecordAdded);
                // Store analytics about recently completed Sessions.
                android.telecom.Log.registerSessionListener(Analytics::addSessionTiming);

                // Ensure LogUtils#initLogging(Context) is called once throughout the entire
                // lifecycle of not only TelecomSystem, but the Testing Framework.
                sInitializedLoggerListeners = true;
                sInitializedCounter++; /* For testing purposes only */
            } else {
                android.telecom.Log.d(LOGUTILS_TAG, "initLogging: called again. Doing nothing.");
            }
        }
    }

    /**
     * Needed in order to test if the registerEventListener & registerSessionListener are ever
     * re-initialized in the entire process of the Testing Framework or TelecomSystem.
     *
     * @return the number of times initLogging(Context) listeners have been initialized
     */
    @VisibleForTesting
    public static int getInitializedCounter() {
        return sInitializedCounter;
    }
}
