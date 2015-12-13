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
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.view.*;
import android.widget.*;
import org.jsl.collider.Collider;
import org.jsl.collider.TimerQueue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class MainActivity extends Activity
{
    public static final String SERVICE_TYPE = "_wfwt._tcp"; /* WiFi Walkie Talkie */
    public static final String SERVICE_NAME = "Channel_00";
    public static final String SERVICE_NAME_SEPARATOR = ":";
    public static final String KEY_STATION_NAME = "station_name";
    public static final String KEY_VOLUME = "volume";
    private static final String LOG_TAG = "MainActivity";

    private static final int DISCOVERY_STATE_START = 1;
    private static final int DISCOVERY_STATE_RUN = 2;

    private int m_audioStream;
    private int m_audioPrvVolume;
    private int m_audioMaxVolume;
    private int m_audioVolume;

    private ListViewAdapter m_listViewAdapter;

    private AudioRecorder m_audioRecorder;
    private String m_stationName;
    private NsdManager m_nsdManager;
    private Collider m_collider;
    private Thread m_colliderThread;
    private Channel m_channel;

    /* We could handle a discovery state with m_discoveryListener only
     * (m_discoveryListener == null means the discovery is not started)
     * but it will not allow us to handle any problems in onCreate().
     */
    private ReentrantLock m_lock;
    private DiscoveryListener m_discoveryListener;
    private int m_discoveryState;
    private Condition m_cond;
    private boolean m_stop;

    private class ButtonTalkTouchListener implements View.OnTouchListener
    {
        /* The proper way to have a button turning background color to red
         * when pressed would be to use a <selector> resource (StateListDrawable),
         * but the idea was to keep the default button style for all states
         * except pressed. Did not found acceptable way to do that, sad...
         */
        private final Button m_button;
        private final Drawable m_defaultBackground;
        private final Drawable m_pressedBackground;

        public ButtonTalkTouchListener( Button button )
        {
            m_button = button;
            m_defaultBackground = button.getBackground();
            m_pressedBackground = new ColorDrawable( getResources().getColor(android.R.color.holo_red_dark) );
        }

        public boolean onTouch( View v, MotionEvent event )
        {
            if (event.getAction() == MotionEvent.ACTION_DOWN)
            {
                m_button.setBackground( m_pressedBackground );
                m_audioRecorder.startRecording();
                return true;
            }
            else if (event.getAction() == MotionEvent.ACTION_UP)
            {
                m_audioRecorder.stopRecording();
                m_button.setBackground( m_defaultBackground );
                return true;
            }
            return false;
        }
    }

    private static class ListViewAdapter extends ArrayAdapter<StationInfo>
    {
        private final LayoutInflater m_inflater;
        private final StringBuilder m_stringBuilder;
        private StationInfo [] m_stationInfo;

        static class RowViewInfo
        {
            public final TextView textViewStationName;
            public final TextView textViewAddrAndPing;
            public final StateView stateView;

            public RowViewInfo( TextView textViewStationName, TextView textViewAddrAndPing, StateView stateView )
            {
                this.textViewStationName = textViewStationName;
                this.textViewAddrAndPing = textViewAddrAndPing;
                this.stateView = stateView;
            }
        }

        public ListViewAdapter( Context context )
        {
            super( context, R.layout.list_view_row );
            m_inflater = (LayoutInflater) context.getSystemService( LAYOUT_INFLATER_SERVICE );
            m_stringBuilder = new StringBuilder();
            m_stationInfo = new StationInfo[0];
        }

        public void setStationInfo( StationInfo [] stationInfo )
        {
            m_stationInfo = stationInfo;
            notifyDataSetChanged();
        }

        public int getCount()
        {
            return m_stationInfo.length;
        }

        public View getView( int position, View convertView, ViewGroup parent )
        {
            View rowView = convertView;
            RowViewInfo rowViewInfo;
            if (rowView == null)
            {
                rowView = m_inflater.inflate( R.layout.list_view_row, null, true );
                final TextView textViewStationName = (TextView) rowView.findViewById( R.id.textViewStationName );
                final TextView textViewStationAddr = (TextView) rowView.findViewById( R.id.textViewAddrAndPing );
                final StateView stateView = (StateView) rowView.findViewById( R.id.stateView );
                rowViewInfo = new RowViewInfo( textViewStationName, textViewStationAddr, stateView );
                rowView.setTag( rowViewInfo );
            }
            else
                rowViewInfo = (RowViewInfo) rowView.getTag();

            rowViewInfo.textViewStationName.setText( m_stationInfo[position].name );

            m_stringBuilder.setLength(0);
            m_stringBuilder.append( m_stationInfo[position].addr );
            final long ping = m_stationInfo[position].ping;
            if (ping > 0)
            {
                m_stringBuilder.append( ", " );
                m_stringBuilder.append( m_stationInfo[position].ping );
                m_stringBuilder.append( " ms" );
            }
            rowViewInfo.textViewAddrAndPing.setText( m_stringBuilder.toString() );
            rowViewInfo.stateView.setState( m_stationInfo[position].state );

            return rowView;
        }
    }

    private class SettingsDialogClickListener implements DialogInterface.OnClickListener
    {
        private final EditText m_editTextStationName;
        private final SeekBar m_seekBarVolume;

        public SettingsDialogClickListener(
                EditText editTextStationName,
                SeekBar seekBarVolume )
        {
            m_editTextStationName = editTextStationName;
            m_seekBarVolume = seekBarVolume;
        }

        public void onClick( DialogInterface dialog, int which )
        {
            m_stationName = m_editTextStationName.getText().toString();
            final String title = getString(R.string.app_name) + " : " + m_stationName;
            setTitle( title );
            m_audioVolume = m_seekBarVolume.getProgress();
            Log.i( LOG_TAG, "audioVolume=" + m_audioVolume );
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

        public void onDiscoveryStopped( String serviceType )
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
                final String [] ss = nsdServiceInfo.getServiceName().split( SERVICE_NAME_SEPARATOR );
                final String channelName = new String( Base64.decode(ss[0], 0) );
                Log.i( LOG_TAG, "service lost: " + channelName + " [" + nsdServiceInfo + "]" );
                if (channelName.compareTo(SERVICE_NAME) == 0)
                    m_channel.onServiceLost( nsdServiceInfo );
            }
            catch (final IllegalArgumentException ex)
            {
                /* Base64.decode() can throw an exception, will be better to handle it. */
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

    private String getDeviceID()
    {
        long deviceID = 0;
        final String str = Settings.Secure.getString( getContentResolver(), Settings.Secure.ANDROID_ID );
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

    public void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.main );

        getWindow().addFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );

        final Button buttonTalk = (Button) findViewById( R.id.buttonTalk );
        buttonTalk.setOnTouchListener( new ButtonTalkTouchListener(buttonTalk) );

        m_lock = new ReentrantLock();
        m_cond = m_lock.newCondition();
    }

    public boolean onCreateOptionsMenu( Menu menu )
    {
        getMenuInflater().inflate( R.menu.menu, menu );
        return true;
    }

    public boolean onOptionsItemSelected( MenuItem item )
    {
        final LayoutInflater layoutInflater = LayoutInflater.from( this );
        final AlertDialog.Builder dialogBuilder = new AlertDialog.Builder( this );
        switch (item.getItemId())
        {
            case R.id.actionSettings:
            {
                final View dialogView = layoutInflater.inflate( R.layout.dialog_settings, null );
                final EditText editText = (EditText) dialogView.findViewById( R.id.editTextStationName );
                final SeekBar seekBar = (SeekBar) dialogView.findViewById( R.id.seekBarVolume );
                editText.setText( m_stationName );
                seekBar.setMax( m_audioMaxVolume );
                seekBar.setProgress( m_audioVolume );
                dialogBuilder.setTitle( R.string.settings );
                dialogBuilder.setView( dialogView );
                dialogBuilder.setCancelable( true );
                dialogBuilder.setPositiveButton( getString(R.string.set), new SettingsDialogClickListener(editText, seekBar) );
                dialogBuilder.setNegativeButton( getString(R.string.cancel), null );
                final AlertDialog dialog = dialogBuilder.create();
                dialog.show();
            }
            break;

            case R.id.actionAbout:
            {
                final View dialogView = layoutInflater.inflate( R.layout.dialog_about, null );
                final TextView textView = (TextView) dialogView.findViewById( R.id.textView );
                textView.setMovementMethod( LinkMovementMethod.getInstance() /*new ScrollingMovementMethod()*/ );
                dialogBuilder.setTitle( R.string.about );
                dialogBuilder.setView( dialogView );
                dialogBuilder.setPositiveButton( getString(R.string.close), null );
                final AlertDialog dialog = dialogBuilder.create();
                dialog.show();
            }
            break;
        }
        return super.onOptionsItemSelected( item );
    }

    public void onResume()
    {
        super.onResume();
        Log.i( LOG_TAG, "onResume" );

        final String deviceID = getDeviceID();

        m_listViewAdapter = new ListViewAdapter( this );
        final ListView listView = (ListView) findViewById( R.id.listView );
        listView.setAdapter( m_listViewAdapter );

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

        final SessionManager sessionManager = new SessionManager();
        m_audioRecorder = AudioRecorder.create( sessionManager, /*repeat*/false );

        final SharedPreferences sharedPreferences = getPreferences( Context.MODE_PRIVATE );
        m_stationName = sharedPreferences.getString( KEY_STATION_NAME, "" );
        if ((m_stationName == null) || m_stationName.isEmpty())
            m_stationName = Build.MODEL;

        if (!m_stationName.isEmpty())
        {
            final String title = getString(R.string.app_name) + " : " + m_stationName;
            setTitle( title );
        }

        /* set maximum volume by default */
        m_audioStream = AudioManager.STREAM_MUSIC;
        final AudioManager audioManager = (AudioManager) getSystemService( AUDIO_SERVICE );
        m_audioPrvVolume = audioManager.getStreamVolume( m_audioStream );
        m_audioMaxVolume = audioManager.getStreamMaxVolume( m_audioStream );
        m_audioVolume = m_audioMaxVolume;
        String str = sharedPreferences.getString( KEY_VOLUME, "" );
        if (!str.isEmpty())
        {
            try
            {
                m_audioVolume = Integer.parseInt( str );
            }
            catch (final NumberFormatException ex)
            {
                m_audioVolume = audioManager.getStreamMaxVolume( m_audioStream );
            }
        }
        audioManager.setStreamVolume( m_audioStream, m_audioVolume, 0 );

        m_nsdManager = (NsdManager) getSystemService( NSD_SERVICE );
        if (m_nsdManager == null)
        {
            final AlertDialog.Builder builder = new AlertDialog.Builder( this );
            builder.setTitle( getString( R.string.system_error ) );
            builder.setMessage( getString(R.string.nsd_not_found) );
            builder.setPositiveButton( getString(R.string.close), null );
            final AlertDialog alertDialog = builder.create();
            alertDialog.show();
            finish();
            return;
        }

        final TimerQueue timerQueue = new TimerQueue( m_collider.getThreadPool() );
        m_colliderThread = new ColliderThread();
        m_colliderThread.start();

        /* Show the channel name with gray color at start,
         * and change color to green after registration.
         */
        final TextView textView = (TextView) findViewById( R.id.textViewStatus );
        textView.setText( SERVICE_NAME );
        textView.setTextColor( Color.GRAY );

        m_channel = new Channel(
                deviceID,
                m_stationName,
                m_audioRecorder.getAudioFormat(),
                this,
                m_collider,
                m_nsdManager,
                SERVICE_TYPE,
                SERVICE_NAME,
                sessionManager,
                timerQueue,
                Config.PING_INTERVAL );

        m_stop = false;
        m_discoveryState = DISCOVERY_STATE_START;
        m_discoveryListener = new DiscoveryListener();
        m_nsdManager.discoverServices( SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, m_discoveryListener );
    }

    public void onPause()
    {
        super.onPause();
        Log.i( LOG_TAG, "onPause" );

        /*** persist settings ***/

        final SharedPreferences sharedPreferences = getPreferences( Context.MODE_PRIVATE );
        final SharedPreferences.Editor editor = sharedPreferences.edit();
        int updates = 0;

        String str = sharedPreferences.getString( KEY_STATION_NAME, "" );
        if (m_stationName.compareTo(str) != 0)
        {
            editor.putString( KEY_STATION_NAME, m_stationName );
            updates++;
        }

        str = sharedPreferences.getString( KEY_VOLUME, "" );
        final String strVolume = Integer.toString( m_audioVolume );
        if (str.compareTo(strVolume) != 0)
        {
            editor.putString( KEY_VOLUME, strVolume );
            updates++;
        }

        if (updates > 0)
            editor.apply();

        /*** restore previous volume ***/

        final AudioManager audioManager = (AudioManager) getSystemService( AUDIO_SERVICE );
        audioManager.setStreamVolume( m_audioStream, m_audioPrvVolume, 0 );

        /*** stop discovery ***/

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
        m_channel.stop( phaser );
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

        Log.i( LOG_TAG, "onPause: done" );
    }

    public void onChannelStarted( final String channelName, final int portNumber )
    {
        runOnUiThread( new Runnable() {
            public void run()
            {
                final TextView textView = (TextView) findViewById( R.id.textViewStatus );
                final StringBuilder sb = new StringBuilder();
                sb.append( channelName );
                sb.append( getString(R.string._colon) );
                sb.append( Integer.toString(portNumber) );
                textView.setText( sb.toString() );
            }
        });
    }

    public void onChannelRegistered( final String serviceName )
    {
        runOnUiThread( new Runnable() {
            public void run()
            {
                final TextView textView = (TextView) findViewById( R.id.textViewStatus );
                String str = textView.getText().toString();
                str += "\n";
                str += serviceName;
                textView.setText( str );
                textView.setTextColor( Color.GREEN );
            }
        });
    }

    public void onStationListChanged( final StationInfo [] stationInfo )
    {
        runOnUiThread( new Runnable() {
            public void run()
            {
                m_listViewAdapter.setStationInfo( stationInfo );
            }
        } );
    }
}
