/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of WiFi WalkieTalkie application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.wfwt;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import org.jsl.collider.Collider;
import org.jsl.collider.TimerQueue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class WalkieService extends Service
{
    private static final String LOG_TAG = WalkieService.class.getSimpleName();

    private static final String SERVICE_TYPE = "_wfwt._tcp"; /* WiFi Walkie Talkie */
    private static final String SERVICE_NAME = "Channel_00";
    static final String SERVICE_NAME_SEPARATOR = ":";

    private NsdManager m_nsdManager;
    private AudioRecorder m_audioRecorder;
    private int m_audioPrvVolume;

    private final Binder m_binder;
    private final ReentrantLock m_lock;
    private Condition m_cond;
    private DiscoveryListener m_discoveryListener;
    private boolean m_discoveryStarted;

    private Collider m_collider;
    private ColliderThread m_colliderThread;
    private Channel m_channel;

    public interface StateListener
    {
        void onInit( AudioRecorder audioRecorder );
    }

    class BinderImpl extends Binder
    {
        void setStateListener(StateListener stateListener, Channel.StateListener channelStateListener)
        {
            stateListener.onInit(m_audioRecorder);
            m_channel.setStateListener(channelStateListener);
        }

        void setStationName(String stationName)
        {
            m_channel.setStationName(stationName);
        }
    }

    private static class ColliderThread extends Thread
    {
        private final Collider m_collider;

        ColliderThread(Collider collider)
        {
            super("ColliderThread");
            m_collider = collider;
        }

        public void run()
        {
            Log.i(LOG_TAG, "Collider thread: start");
            m_collider.run();
            Log.i(LOG_TAG, "Collider thread: done");
        }
    }

    private class DiscoveryListener implements NsdManager.DiscoveryListener
    {
        public void onStartDiscoveryFailed( String serviceType, int errorCode )
        {
            Log.e( LOG_TAG, "Start discovery failed: " + errorCode );
            m_lock.lock();
            try
            {
                if (m_cond != null)
                    m_cond.signal();
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onStopDiscoveryFailed( String serviceType, int errorCode )
        {
            Log.e( LOG_TAG, "Stop discovery failed: " + errorCode );
        }

        public void onDiscoveryStarted( String serviceType )
        {
            Log.i( LOG_TAG, "Discovery started" );
            m_lock.lock();
            try
            {
                if (m_cond == null)
                    m_discoveryStarted = true;
                else
                    m_nsdManager.stopServiceDiscovery( this );
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onDiscoveryStopped( String serviceType )
        {
            Log.i( LOG_TAG, "Discovery stopped" );
            m_lock.lock();
            try
            {
                m_cond.signal();
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onServiceFound( NsdServiceInfo nsdServiceInfo )
        {
            try
            {
                final String[] ss = nsdServiceInfo.getServiceName().split( SERVICE_NAME_SEPARATOR );
                final String channelName = new String( Base64.decode( ss[0], 0 ) );
                Log.i( LOG_TAG, "onServiceFound: " + channelName + ": " + nsdServiceInfo );
                if (channelName.compareTo( SERVICE_NAME ) == 0)
                    m_channel.onServiceFound( nsdServiceInfo );
            }
            catch (final IllegalArgumentException ex)
            {
                /* Base64.decode() can throw an exception,
                 * will be better to handle it.
                 */
                Log.w( LOG_TAG, ex.toString() );
            }
        }

        public void onServiceLost( NsdServiceInfo nsdServiceInfo )
        {
            try
            {
                final String[] ss = nsdServiceInfo.getServiceName().split( SERVICE_NAME_SEPARATOR );
                final String channelName = new String( Base64.decode( ss[0], 0 ) );
                Log.i( LOG_TAG, "service lost: " + channelName + " [" + nsdServiceInfo + "]" );
                if (channelName.compareTo( SERVICE_NAME ) == 0)
                    m_channel.onServiceLost( nsdServiceInfo );
            }
            catch (final IllegalArgumentException ex)
            {
                /* Base64.decode() can throw an exception, will be better to handle it. */
                Log.w( LOG_TAG, ex.toString() );
            }
        }
    }

    private static String getDeviceID( ContentResolver contentResolver )
    {
        long deviceID = 0;
        final String str = Settings.Secure.getString( contentResolver, Settings.Secure.ANDROID_ID );
        if (str != null)
        {
            try
            {
                final BigInteger bi = new BigInteger( str, 16 );
                deviceID = bi.longValue();
            }
            catch (final NumberFormatException ex)
            {
                /* Nothing critical */
                Log.i( LOG_TAG, ex.toString() );
            }
        }

        if (deviceID == 0)
        {
            /* Let's use random number */
            deviceID = new Random().nextLong();
        }

        final byte [] bb = new byte[Long.SIZE / Byte.SIZE];
        for (int idx=(bb.length - 1); idx>=0; idx--)
        {
            bb[idx] = (byte) (deviceID & 0xFF);
            deviceID >>= Byte.SIZE;
        }

        return Base64.encodeToString( bb, (Base64.NO_PADDING | Base64.NO_WRAP) );
    }

    public WalkieService()
    {
        m_binder = new BinderImpl();
        m_lock = new ReentrantLock();
    }

    public void onCreate()
    {
        super.onCreate();
        Log.d( LOG_TAG, "onCreate" );
        m_nsdManager = (NsdManager) getSystemService( NSD_SERVICE );
    }

    public IBinder onBind( Intent intent )
    {
        Log.d( LOG_TAG, "onBind" );
        return m_binder;
    }

    public boolean onUnbind( Intent intent )
    {
        Log.d( LOG_TAG, "onUnbind" );
        m_channel.setStateListener( null );
        return false;
    }

    public int onStartCommand(Intent intent, int flags, int startId)
    {
        Log.d( LOG_TAG, "onStartCommand: flags=" + flags + " startId=" + startId );

        if (m_audioRecorder == null)
        {
            final String deviceID = getDeviceID( getContentResolver() );

            final SessionManager sessionManager = new SessionManager();
            m_audioRecorder = AudioRecorder.create(sessionManager);

            if (m_audioRecorder != null)
            {
                startForeground( 0, null );

                final int audioStream = MainActivity.AUDIO_STREAM;
                final AudioManager audioManager = (AudioManager) getSystemService( AUDIO_SERVICE );
                m_audioPrvVolume = audioManager.getStreamVolume( audioStream );

                final String stationName = intent.getStringExtra( MainActivity.KEY_STATION_NAME );
                int audioVolume = intent.getIntExtra( MainActivity.KEY_VOLUME, -1 );
                if (audioVolume < 0)
                    audioVolume = audioManager.getStreamMaxVolume( audioStream );
                Log.d( LOG_TAG, "setStreamVolume(" + audioStream + ", " + audioVolume + ")" );
                audioManager.setStreamVolume( audioStream, audioVolume, 0 );

                try
                {
                    final Collider.Config colliderConfig = new Collider.Config();
                    colliderConfig.threadPriority = Thread.MAX_PRIORITY;
                    m_collider = Collider.create(colliderConfig);
                    m_colliderThread = new ColliderThread(m_collider);
                    m_colliderThread.setPriority(colliderConfig.threadPriority);

                    final TimerQueue timerQueue = new TimerQueue(m_collider.getThreadPool());

                    m_channel = new Channel(
                            deviceID,
                            stationName,
                            m_audioRecorder.getAudioFormat(),
                            m_collider,
                            m_nsdManager,
                            SERVICE_TYPE,
                            SERVICE_NAME,
                            sessionManager,
                            timerQueue,
                            Config.PING_INTERVAL);

                    m_discoveryListener = new DiscoveryListener();
                    m_nsdManager.discoverServices( SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, m_discoveryListener );
                    m_colliderThread.start();
                }
                catch (final IOException ex)
                {
                    Log.w( LOG_TAG, ex.toString() );
                }
            }
        }
        return START_REDELIVER_INTENT;
    }

    public void onDestroy()
    {
        Log.d( LOG_TAG, "onDestroy" );

        if (m_audioRecorder != null)
        {
            m_audioRecorder.shutdown();
            m_audioRecorder = null;
        }

        boolean interrupted = false;

        m_lock.lock();
        try
        {
            if (m_discoveryListener != null)
            {
                final Condition cond = m_lock.newCondition();
                m_cond = cond;

                if (m_discoveryStarted)
                    m_nsdManager.stopServiceDiscovery( m_discoveryListener );

                cond.await();
            }
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, ex.toString() );
            interrupted = true;
        }
        finally
        {
            m_lock.unlock();
        }

        /* Channel has 2 possible operations to stop:
         *    - service registration
         *    - service resolve
         */
        final CountDownLatch stopLatch = new CountDownLatch( 2 );
        m_channel.stop( stopLatch );

        try
        {
            stopLatch.await();
        }
        catch (final InterruptedException ex)
        {
            Log.w( LOG_TAG, ex.toString() );
            interrupted = true;
        }

        if (m_collider != null)
        {
            m_collider.stop();

            try
            {
                m_colliderThread.join();
            }
            catch (final InterruptedException ex)
            {
                Log.d( LOG_TAG, ex.toString() );
                interrupted = true;
            }
        }

        /* Restore volume */
        final int audioStream = MainActivity.AUDIO_STREAM;
        final AudioManager audioManager = (AudioManager) getSystemService( AUDIO_SERVICE );
        audioManager.setStreamVolume( audioStream, m_audioPrvVolume, 0 );

        if (interrupted)
            Thread.currentThread().interrupt();

        Log.d( LOG_TAG, "onDestroy: done" );
        super.onDestroy();
    }
}
