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

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.*;
import android.widget.*;
import org.jsl.collider.Collider;
import org.jsl.collider.TimerQueue;

import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends Activity
{
    public static final String SERVICE_TYPE = "_wfwt._tcp"; /* WiFi Walkie Talkie */
    public static final String SERVICE_NAME = "Channel_00";
    public static final String SERVICE_NAME_SEPARATOR = ":";
    private static final String LOG_TAG = "MainActivity";

    private static final int DISCOVERY_STATE_START = 1;
    private static final int DISCOVERY_STATE_RUN = 2;

    private SessionManager m_sessionManager;
    private AudioRecorder m_audioRecorder;
    private NsdManager m_nsdManager;
    private Collider m_collider;
    private Thread m_colliderThread;
    private TimerQueue m_timerQueue;
    private int m_pingInterval;
    private Channel m_channel;

    /* We could handle a discovery state with m_discoveryListener only
     * (m_discoveryListener==null means the discovery is not started)
     * but it will not allow us to handle any problems in onCreate().
     */
    private ReentrantLock m_lock;
    private DiscoveryListener m_discoveryListener;
    private int m_discoveryState;
    private Condition m_cond;
    private boolean m_stop;

    private class ButtonTalkTouchListener implements View.OnTouchListener
    {
        public boolean onTouch( View v, MotionEvent event )
        {
            if (event.getAction() == MotionEvent.ACTION_DOWN)
                m_audioRecorder.startRecording();
            else if (event.getAction() == MotionEvent.ACTION_UP)
                m_audioRecorder.stopRecording();
            return false;
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
                m_discoveryState = 0;
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
                if (m_stop)
                    m_nsdManager.stopServiceDiscovery( this );
                else
                    m_discoveryState = DISCOVERY_STATE_RUN;
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onDiscoveryStopped( String serviceType)
        {
            Log.i( LOG_TAG, "Discovery stopped" );
            m_lock.lock();
            try
            {
                m_discoveryState = 0;
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
                final String [] ss = nsdServiceInfo.getServiceName().split( SERVICE_NAME_SEPARATOR );
                final String channelName = new String( Base64.decode(ss[0], 0) );
                Log.i( LOG_TAG, "service found: " + channelName + " [" + nsdServiceInfo + "]" );
                if (channelName.compareTo(SERVICE_NAME) == 0)
                    m_channel.addService( nsdServiceInfo );
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
                final String [] ss = nsdServiceInfo.getServiceName().split( SERVICE_NAME_SEPARATOR );
                final String channelName = new String( Base64.decode(ss[0], 0) );
                Log.i( LOG_TAG, "service lost: " + channelName + " [" + nsdServiceInfo + "]" );
                if (channelName.compareTo(SERVICE_NAME) == 0)
                {
                    final NsdServiceInfo final_nsdServiceInfo = nsdServiceInfo;
                    runOnUiThread( new Runnable() {
                        public void run() {
                            m_channel.removeService(final_nsdServiceInfo);
                        }
                    });
                }
            }
            catch (final IllegalArgumentException ex)
            {
                /* Base64.decode() can theow an exception,
                 * will be better to handle it.
                 */
                Log.w( LOG_TAG, ex.toString() );
            }
        }
    }

    private class ColliderThread extends Thread
    {
        public ColliderThread()
        {
            super( "ColliderThread" );
        }

        public void run()
        {
            Log.i( LOG_TAG, "Collider thread: start" );
            m_collider.run();
            Log.i( LOG_TAG, "Collider thread: done" );
        }
    }

    public void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.main );

        getWindow().addFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );

        final Button buttonTalk = (Button) findViewById( R.id.buttonTalk );
        buttonTalk.setOnTouchListener( new ButtonTalkTouchListener() );

        m_lock = new ReentrantLock();
        m_cond = m_lock.newCondition();
    }

    public void onResume()
    {
        super.onResume();
        Log.i( LOG_TAG, "onResume" );

        try
        {
            m_collider = Collider.create();
        }
        catch (final IOException ex)
        {
            Log.e( LOG_TAG, ex.toString() );

            final AlertDialog.Builder builder = new AlertDialog.Builder( this );
            builder.setTitle( getString(R.string.system_error) );
            builder.setMessage( getString(R.string.network_initialization_failed) );
            builder.setPositiveButton( getString(R.string.close), null );
            final AlertDialog alertDialog = builder.create();
            alertDialog.show();
            finish();
            return;
        }

        m_sessionManager = new SessionManager();
        m_audioRecorder = AudioRecorder.create( m_sessionManager, /*repeat*/true );

        m_nsdManager = (NsdManager) getSystemService( NSD_SERVICE );
        if (m_nsdManager == null)
        {
            final AlertDialog.Builder builder = new AlertDialog.Builder( this );
            builder.setTitle( getString(R.string.system_error) );
            builder.setMessage( getString(R.string.nsd_not_found) );
            builder.setPositiveButton( getString(R.string.close), null );
            final AlertDialog alertDialog = builder.create();
            alertDialog.show();
            finish();
            return;
        }

        m_timerQueue = new TimerQueue( m_collider.getThreadPool() );
        m_pingInterval = 5;
        m_colliderThread = new ColliderThread();
        m_colliderThread.start();

        /* Show the channel name with gray color at start,
         * and change color to green after registration.
         */
        final TextView textView = (TextView) findViewById( R.id.textChannelState );
        textView.setText( SERVICE_NAME );
        textView.setTextColor( Color.GRAY );

        m_channel = new Channel(
                m_audioRecorder.getAudioFormat(),
                this,
                m_collider,
                m_nsdManager,
                SERVICE_TYPE,
                SERVICE_NAME,
                m_timerQueue,
                m_pingInterval );

        m_stop = false;
        m_discoveryState = DISCOVERY_STATE_START;
        m_discoveryListener = new DiscoveryListener();
        m_nsdManager.discoverServices( SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, m_discoveryListener );
    }

    public void onPause()
    {
        super.onPause();
        Log.i( LOG_TAG, "onPause" );

        boolean stopDiscovery = false;
        m_lock.lock();
        try
        {
            m_stop = true;
            if (m_discoveryState == DISCOVERY_STATE_START)
            {
                /* Can wait right now. */
                while (m_discoveryState != 0)
                    m_cond.await();
            }
            else if (m_discoveryState == DISCOVERY_STATE_RUN)
            {
                stopDiscovery = true;
            }
            else
            {
                if (BuildConfig.DEBUG)
                    throw new AssertionError();
            }
        }
        catch (final InterruptedException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
            Thread.currentThread().interrupt();
        }
        finally
        {
            m_lock.unlock();
        }

        Log.i( LOG_TAG, "onPause: 1" );

        if (stopDiscovery)
        {
            m_nsdManager.stopServiceDiscovery( m_discoveryListener );
            m_lock.lock();
            try
            {
                while (m_discoveryState != 0)
                    m_cond.await();
            }
            catch (final InterruptedException ex)
            {
                Log.e( LOG_TAG, ex.toString() );
            }
            finally
            {
                m_lock.unlock();
            }
        }

        Log.i( LOG_TAG, "onPause: 2" );

        /* Stop and wait all channels */
        final Phaser phaser = new Phaser();
        final int phase1 = phaser.register();
        m_channel.stop(phaser);
        final int phase2 = phaser.arrive();
        if (BuildConfig.DEBUG && (phase1 != phase2))
            throw new AssertionError();
        phaser.awaitAdvance( phase2 );

        if (m_colliderThread != null)
        {
            m_collider.stop();
            try
            {
                m_colliderThread.join();
            }
            catch (final InterruptedException ex)
            {
                Log.e( LOG_TAG, ex.toString() );
                Thread.currentThread().interrupt();
            }
            m_colliderThread = null;
            m_collider = null;
        }

        Log.i( LOG_TAG, "onPause: 3" );

        if (m_audioRecorder != null)
        {
            m_audioRecorder.shutdown();
            m_audioRecorder = null;
        }

        Log.i( LOG_TAG, "onPause: done." );
    }

    public void onServiceRegistered( final String channelName )
    {
        runOnUiThread( new Runnable() {
            public void run()
            {
                final TextView textView = (TextView) findViewById( R.id.textChannelState );
                textView.setText( channelName );
                textView.setTextColor( Color.GREEN );
            }
        });
    }
}
