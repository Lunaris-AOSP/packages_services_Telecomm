/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License.
 */
package com.android.server.telecom.tests;

import static com.android.server.telecom.TelecomStatsLog.CALL_AUDIO_ROUTE_STATS;
import static com.android.server.telecom.TelecomStatsLog.CALL_STATS;
import static com.android.server.telecom.TelecomStatsLog.TELECOM_API_STATS;
import static com.android.server.telecom.TelecomStatsLog.TELECOM_ERROR_STATS;
import static com.android.server.telecom.TelecomStatsLog.TELECOM_EVENT_STATS;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.StatsManager;
import android.os.HandlerThread;
import android.util.StatsEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.telecom.metrics.ApiStats;
import com.android.server.telecom.metrics.AudioRouteStats;
import com.android.server.telecom.metrics.CallStats;
import com.android.server.telecom.metrics.ErrorStats;
import com.android.server.telecom.metrics.EventStats;
import com.android.server.telecom.metrics.TelecomMetricsController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TelecomMetricsControllerTest extends TelecomTestCase {

    @Mock
    ApiStats mApiStats;
    @Mock
    AudioRouteStats mAudioRouteStats;
    @Mock
    CallStats mCallStats;
    @Mock
    ErrorStats mErrorStats;
    @Mock
    EventStats mEventStats;

    HandlerThread mHandlerThread;

    TelecomMetricsController mTelecomMetricsController;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        mHandlerThread = new HandlerThread("TelecomMetricsControllerTest");
        mHandlerThread.start();
        mTelecomMetricsController = TelecomMetricsController.make(mContext, mHandlerThread);
        assertThat(mTelecomMetricsController).isNotNull();
        setUpStats();
    }

    @Override
    @After
    public void tearDown() throws Exception {
        mTelecomMetricsController.destroy();
        mHandlerThread.quitSafely();
        super.tearDown();
    }

    @Test
    public void testGetApiStatsReturnsSameInstance() {
        ApiStats stats1 = mTelecomMetricsController.getApiStats();
        ApiStats stats2 = mTelecomMetricsController.getApiStats();
        assertThat(stats1).isSameInstanceAs(stats2);
    }

    @Test
    public void testGetAudioRouteStatsReturnsSameInstance() {
        AudioRouteStats stats1 = mTelecomMetricsController.getAudioRouteStats();
        AudioRouteStats stats2 = mTelecomMetricsController.getAudioRouteStats();
        assertThat(stats1).isSameInstanceAs(stats2);
    }

    @Test
    public void testGetCallStatsReturnsSameInstance() {
        CallStats stats1 = mTelecomMetricsController.getCallStats();
        CallStats stats2 = mTelecomMetricsController.getCallStats();
        assertThat(stats1).isSameInstanceAs(stats2);
    }

    @Test
    public void testGetErrorStatsReturnsSameInstance() {
        ErrorStats stats1 = mTelecomMetricsController.getErrorStats();
        ErrorStats stats2 = mTelecomMetricsController.getErrorStats();
        assertThat(stats1).isSameInstanceAs(stats2);
    }

    @Test
    public void testGetEventStatsReturnsSameInstance() {
        EventStats stats1 = mTelecomMetricsController.getEventStats();
        EventStats stats2 = mTelecomMetricsController.getEventStats();
        assertThat(stats1).isSameInstanceAs(stats2);
    }

    @Test
    public void testOnPullAtomReturnsPullSkipIfAtomNotRegistered() {
        mTelecomMetricsController.getStats().clear();

        int result = mTelecomMetricsController.onPullAtom(TELECOM_API_STATS, null);
        assertThat(result).isEqualTo(StatsManager.PULL_SKIP);
    }

    @Test
    public void testRegisterAtom() {
        StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        ApiStats stats = mock(ApiStats.class);

        mTelecomMetricsController.registerAtom(TELECOM_API_STATS, stats);

        verify(statsManager, times(1)).setPullAtomCallback(eq(TELECOM_API_STATS), any(),
                any(), eq(mTelecomMetricsController));
        assertThat(mTelecomMetricsController.getStats().get(TELECOM_API_STATS))
                .isSameInstanceAs(stats);
    }

    @Test
    public void testDestroy() {
        StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        mTelecomMetricsController.destroy();

        verify(statsManager, times(1)).clearPullAtomCallback(eq(CALL_AUDIO_ROUTE_STATS));
        verify(statsManager, times(1)).clearPullAtomCallback(eq(CALL_STATS));
        verify(statsManager, times(1)).clearPullAtomCallback(eq(TELECOM_API_STATS));
        verify(statsManager, times(1)).clearPullAtomCallback(eq(TELECOM_ERROR_STATS));
        verify(statsManager, times(1)).clearPullAtomCallback(eq(TELECOM_EVENT_STATS));
        assertThat(mTelecomMetricsController.getStats()).isEmpty();
    }

    @Test
    public void testOnPullAtomIsPulled() {
        final List<StatsEvent> data = new ArrayList<>();
        final ArgumentCaptor<List<StatsEvent>> captor = ArgumentCaptor.forClass((Class) List.class);
        doReturn(StatsManager.PULL_SUCCESS).when(mApiStats).pull(any());

        int result = mTelecomMetricsController.onPullAtom(TELECOM_API_STATS, data);

        verify(mApiStats).pull(captor.capture());
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        assertThat(captor.getValue()).isEqualTo(data);
    }

    @Test
    public void testSetTestMode() {
        StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        ApiStats apiStats1 = mTelecomMetricsController.getApiStats();
        AudioRouteStats audioStats1 = mTelecomMetricsController.getAudioRouteStats();
        CallStats callStats1 = mTelecomMetricsController.getCallStats();
        ErrorStats errorStats1 = mTelecomMetricsController.getErrorStats();
        mTelecomMetricsController.setTestMode(true);

        verify(statsManager, times(1)).clearPullAtomCallback(eq(CALL_AUDIO_ROUTE_STATS));
        verify(statsManager, times(1)).clearPullAtomCallback(eq(CALL_STATS));
        verify(statsManager, times(1)).clearPullAtomCallback(eq(TELECOM_API_STATS));
        verify(statsManager, times(1)).clearPullAtomCallback(eq(TELECOM_ERROR_STATS));
        assertThat(mTelecomMetricsController.getStats()).isEmpty();

        ApiStats apiStats2 = mTelecomMetricsController.getApiStats();
        AudioRouteStats audioStats2 = mTelecomMetricsController.getAudioRouteStats();
        CallStats callStats2 = mTelecomMetricsController.getCallStats();
        ErrorStats errorStats2 = mTelecomMetricsController.getErrorStats();

        assertThat(apiStats1).isNotSameInstanceAs(apiStats2);
        assertThat(audioStats1).isNotSameInstanceAs(audioStats2);
        assertThat(callStats1).isNotSameInstanceAs(callStats2);
        assertThat(errorStats1).isNotSameInstanceAs(errorStats2);

        mTelecomMetricsController.setTestMode(false);

        assertThat(mTelecomMetricsController.getStats()).isEmpty();
    }

    private void setUpStats() {
        mTelecomMetricsController.getStats().put(CALL_AUDIO_ROUTE_STATS,
                mAudioRouteStats);
        mTelecomMetricsController.getStats().put(CALL_STATS, mCallStats);
        mTelecomMetricsController.getStats().put(TELECOM_API_STATS, mApiStats);
        mTelecomMetricsController.getStats().put(TELECOM_ERROR_STATS, mErrorStats);
        mTelecomMetricsController.getStats().put(TELECOM_EVENT_STATS, mEventStats);
    }
}
