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

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.*;
import android.widget.*;

public class MainActivity extends Activity implements WalkieService.StateListener, Channel.StateListener
{
    private static final String LOG_TAG = "MainActivity";

    public static final int AUDIO_STREAM = AudioManager.STREAM_MUSIC;
    public static final String KEY_STATION_NAME = "station_name";
    public static final String KEY_VOLUME = "volume";
    private static final String KEY_CHECK_WIFI_STATUS = "check-wifi-status";
    private static final String KEY_USE_VOLUME_BUTTONS_TO_TALK = "use-volume-buttons-to-talk";

    private boolean m_exit;
    private Intent m_serviceIntent;
    private ServiceConnection m_serviceConnection;
    private WalkieService.BinderImpl m_binder;
    private AudioRecorder m_audioRecorder;
    private SwitchButton m_buttonTalk;
    private boolean m_useVolumeButtonsToTalk;
    private boolean m_recording;

    private String m_stationName;
    private int m_audioMaxVolume;
    private int m_audioVolume;

    private ListViewAdapter m_listViewAdapter;

    private class ButtonTalkListener implements SwitchButton.StateListener
    {
        public void onStateChanged( boolean state )
        {
            if (state)
            {
                if (!m_recording)
                {
                    m_recording = true;
                    m_audioRecorder.startRecording();
                }
            }
            else
            {
                if (m_recording)
                {
                    m_recording = false;
                    m_audioRecorder.stopRecording();
                }
            }
        }
    }

    private static class ListViewAdapter extends ArrayAdapter<StationInfo>
    {
        private final LayoutInflater m_inflater;
        private final StringBuilder m_stringBuilder;
        private StationInfo [] m_stationInfo;

        private static class RowViewInfo
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
            rowViewInfo.stateView.setIndicatorState( m_stationInfo[position].transmission );

            return rowView;
        }
    }

    private class SettingsDialogClickListener implements DialogInterface.OnClickListener
    {
        private final EditText m_editTextStationName;
        private final SeekBar m_seekBarVolume;
        private final CheckBox m_checkBoxCheckWiFiStateOnStart;
        private final CheckBox m_switchButtonUseVolumeButtonsToTalk;

        public SettingsDialogClickListener(
                EditText editTextStationName,
                SeekBar seekBarVolume,
                CheckBox checkBoxCheckWiFiStateOnStart,
                CheckBox switchButtonUseVolumeButtonsToTalk )
        {
            m_editTextStationName = editTextStationName;
            m_seekBarVolume = seekBarVolume;
            m_checkBoxCheckWiFiStateOnStart = checkBoxCheckWiFiStateOnStart;
            m_switchButtonUseVolumeButtonsToTalk = switchButtonUseVolumeButtonsToTalk;
        }

        public void onClick( DialogInterface dialog, int which )
        {
            if (which == DialogInterface.BUTTON_POSITIVE)
            {
                final String stationName = m_editTextStationName.getText().toString();
                final int audioVolume = m_seekBarVolume.getProgress();

                final SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
                final SharedPreferences.Editor editor = sharedPreferences.edit();

                if (m_stationName.compareTo(stationName) != 0)
                {
                    final String title = getString(R.string.app_name) + ": " + stationName;
                    setTitle(title);

                    editor.putString( KEY_STATION_NAME, stationName );
                    m_binder.setStationName( stationName );
                    m_stationName = stationName;
                }

                if (audioVolume != m_audioVolume)
                {
                    editor.putString( KEY_VOLUME, Integer.toString(audioVolume) );

                    final int audioStream = MainActivity.AUDIO_STREAM;
                    final AudioManager audioManager = (AudioManager) getSystemService( AUDIO_SERVICE );
                    Log.d(LOG_TAG, "setStreamVolume(" + audioStream + ", " + audioVolume + ")");
                    audioManager.setStreamVolume( audioStream, audioVolume, 0 );

                    m_audioVolume = audioVolume;
                }

                final boolean useVolumeButtonsToTalk = m_switchButtonUseVolumeButtonsToTalk.isChecked();
                editor.putBoolean( KEY_CHECK_WIFI_STATUS, m_checkBoxCheckWiFiStateOnStart.isChecked() );
                editor.putBoolean( KEY_USE_VOLUME_BUTTONS_TO_TALK, useVolumeButtonsToTalk );
                editor.apply();

                MainActivity.this.m_useVolumeButtonsToTalk = useVolumeButtonsToTalk;
            }
        }
    }

    /* WalkieService.StateListener interface implementation */

    public void onInit( final AudioRecorder audioRecorder )
    {
        Log.d( LOG_TAG, "onInit" );
        if (audioRecorder != null)
        {
            runOnUiThread( new Runnable() {
                public void run() {
                    m_audioRecorder = audioRecorder;
                    m_buttonTalk.setStateListener( new ButtonTalkListener() );
                    m_buttonTalk.setEnabled( true );
                }
            } );
        }
    }

    /* Channel.StateListener interface implementation */

    public void onStateChanged( final String stateString, final boolean registered )
    {
        Log.d( LOG_TAG, "onStateChanged: " + stateString );
        runOnUiThread( new Runnable() {
            public void run() {
                final TextView textView = (TextView) findViewById( R.id.textViewStatus );
                textView.setText( stateString );
                textView.setTextColor( (registered ? Color.GREEN : Color.GRAY) );
            }
        });
    }

    public void onStationListChanged( final StationInfo [] stationInfo )
    {
        runOnUiThread( new Runnable() {
            public void run() {
                m_listViewAdapter.setStationInfo( stationInfo );
            }
        } );
    }

    public void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );
        Log.d( LOG_TAG, "onCreate" );

        getWindow().addFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );

        setContentView( R.layout.main );

        m_listViewAdapter = new ListViewAdapter( this );
        final ListView listView = (ListView) findViewById( R.id.listView );
        listView.setAdapter( m_listViewAdapter );

        final TextView textView = (TextView) findViewById( R.id.textViewStatus );
        textView.setTextColor( Color.GREEN );

        m_buttonTalk = (SwitchButton) findViewById( R.id.buttonTalk );
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
                final SharedPreferences sharedPreferences = getPreferences( Context.MODE_PRIVATE );
                final View dialogView = layoutInflater.inflate( R.layout.dialog_settings, null );
                final EditText editText = (EditText) dialogView.findViewById( R.id.editTextStationName );
                final SeekBar seekBar = (SeekBar) dialogView.findViewById( R.id.seekBarVolume );
                final CheckBox checkBoxCheckWiFiStatusOnStart = (CheckBox) dialogView.findViewById( R.id.checkBoxCheckWiFiStatusOnStart );
                final CheckBox checkBoxUseVolumeButtonsToTalk = (CheckBox) dialogView.findViewById( R.id.checkBoxUseVolumeButtonsToTalk );

                editText.setText( m_stationName );
                seekBar.setMax( m_audioMaxVolume );
                seekBar.setProgress( m_audioVolume );
                checkBoxCheckWiFiStatusOnStart.setChecked( sharedPreferences.getBoolean(KEY_CHECK_WIFI_STATUS, true) );
                checkBoxUseVolumeButtonsToTalk.setChecked( sharedPreferences.getBoolean(KEY_USE_VOLUME_BUTTONS_TO_TALK, false) );
                dialogBuilder.setTitle( R.string.settings );
                dialogBuilder.setView( dialogView );
                dialogBuilder.setCancelable( true );
                dialogBuilder.setPositiveButton( getString(R.string.set), new SettingsDialogClickListener(
                        editText, seekBar, checkBoxCheckWiFiStatusOnStart, checkBoxUseVolumeButtonsToTalk) );
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

            case R.id.actionExit:
                m_exit = true;
                finish();
            break;
        }
        return super.onOptionsItemSelected( item );
    }

    public void onResume()
    {
        super.onResume();
        Log.i( LOG_TAG, "onResume" );

        final NotificationManager notificationManager = (NotificationManager) getSystemService( NOTIFICATION_SERVICE );
        notificationManager.cancelAll();

        m_exit = false;
        m_listViewAdapter.clear();

        final SharedPreferences sharedPreferences = getPreferences( Context.MODE_PRIVATE );
        m_stationName = sharedPreferences.getString( KEY_STATION_NAME, null );
        if ((m_stationName == null) || m_stationName.isEmpty())
            m_stationName = Build.MODEL;

        m_useVolumeButtonsToTalk = sharedPreferences.getBoolean( KEY_USE_VOLUME_BUTTONS_TO_TALK, false );

        if (!m_stationName.isEmpty())
        {
            final String title = getString(R.string.app_name) + ": " + m_stationName;
            setTitle( title );
        }

        final int audioStream = MainActivity.AUDIO_STREAM;
        final AudioManager audioManager = (AudioManager) getSystemService( AUDIO_SERVICE );
        m_audioMaxVolume = audioManager.getStreamMaxVolume( audioStream );

        final String str = sharedPreferences.getString( KEY_VOLUME, null );
        if ((str == null) || str.isEmpty())
            m_audioVolume = m_audioMaxVolume;
        else
        {
            try
            {
                m_audioVolume = Integer.parseInt( str );
            }
            catch (final NumberFormatException ex)
            {
                m_audioVolume = m_audioMaxVolume;
            }
        }

        m_serviceIntent = new Intent( this, WalkieService.class );
        m_serviceIntent.putExtra( KEY_STATION_NAME, m_stationName );
        m_serviceIntent.putExtra( KEY_VOLUME, m_audioVolume );
        final ComponentName componentName = startService( m_serviceIntent );

        m_serviceConnection = new ServiceConnection()
        {
            public void onServiceConnected( ComponentName name, IBinder binder )
            {
                Log.d( LOG_TAG, "onServiceConnected" );
                m_binder = (WalkieService.BinderImpl) binder;
                m_binder.setStateListener(
                        /*WalkieService.StateListener*/ MainActivity.this,
                        /*Channel.StateListener*/ MainActivity.this );
            }

            public void onServiceDisconnected( ComponentName name )
            {
                Log.d( LOG_TAG, "onServiceDisconnected" );
            }
        };

        final boolean bindRC = bindService( m_serviceIntent, m_serviceConnection, BIND_AUTO_CREATE );
        if (!bindRC)
            m_serviceConnection = null;
        Log.d( LOG_TAG, "componentName=" + componentName + " bindRC=" + bindRC );

        /*** Check WiFi status */

        final boolean checkWiFiStatus = sharedPreferences.getBoolean( KEY_CHECK_WIFI_STATUS, true );
        if (checkWiFiStatus)
        {
            final WifiManager manager = (WifiManager) getSystemService( WIFI_SERVICE );
            if (!manager.isWifiEnabled())
            {
                final LayoutInflater layoutInflater = LayoutInflater.from( this );
                final AlertDialog.Builder dialogBuilder = new AlertDialog.Builder( this );
                final View dialogView = layoutInflater.inflate( R.layout.dialog_wifi, null );
                final DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Log.d(LOG_TAG, "WiFiDialog: which=" + which);
                        if (which == DialogInterface.BUTTON_POSITIVE) {
                            manager.setWifiEnabled(true);
                        }
                        final CheckBox checkBox = (CheckBox) dialogView.findViewById( R.id.checkBoxNeverAskAgain );
                        if (checkBox.isChecked()) {
                            final SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putBoolean( KEY_CHECK_WIFI_STATUS, true );
                            editor.apply();
                        }
                    }
                };
                dialogBuilder.setView( dialogView );
                dialogBuilder.setPositiveButton( getString(R.string.turn_wifi_on), listener );
                dialogBuilder.setNegativeButton( getString(R.string.cancel), listener );
                final AlertDialog dialog = dialogBuilder.create();
                dialog.show();
            }
        }
    }

    public void onPause()
    {
        super.onPause();
        Log.i( LOG_TAG, "onPause" );

        if (m_serviceConnection != null)
        {
            unbindService( m_serviceConnection );
            m_serviceConnection = null;
        }

        if (m_serviceIntent != null)
        {
            if (m_exit)
                stopService( m_serviceIntent );
            else
            {
                final Intent intent = new Intent( this, MainActivity.class );
                intent.setFlags( Intent.FLAG_ACTIVITY_SINGLE_TOP );
                final PendingIntent pendingIntent = PendingIntent.getActivity( this, (int) System.currentTimeMillis(), intent, 0 );
                final Notification.Builder notificationBuilder = new Notification.Builder( this );
                notificationBuilder.setContentTitle( getString(R.string.app_name) );
                notificationBuilder.setContentText( getString(R.string.running) );
                notificationBuilder.setContentIntent( pendingIntent );
                notificationBuilder.setSmallIcon( R.drawable.ic_status );

                final Bitmap largeIcon = BitmapFactory.decodeResource( getResources(), R.drawable.ic_launcher );
                notificationBuilder.setLargeIcon( largeIcon );

                final Notification notification = notificationBuilder.build();
                notification.flags |= Notification.FLAG_AUTO_CANCEL;

                final NotificationManager notificationManager = (NotificationManager) getSystemService( NOTIFICATION_SERVICE );
                notificationManager.notify( 0, notification );
            }
            m_serviceIntent = null;
        }

        Log.i( LOG_TAG, "onPause: done" );
    }

    public void onDestroy()
    {
        Log.i( LOG_TAG, "onDestroy" );
        super.onDestroy();
    }

    public boolean onKeyDown( int keyCode, KeyEvent event )
    {
        if (m_useVolumeButtonsToTalk)
        {
            if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP) ||
                (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN))
            {
                if (!m_recording)
                {
                    m_audioRecorder.startRecording();
                    m_recording = true;
                    m_buttonTalk.setPressed( true );
                }
                return true;
            }
        }
        return super.onKeyDown( keyCode, event );
    }

    public boolean onKeyUp( int keyCode, KeyEvent event )
    {
        if (m_useVolumeButtonsToTalk)
        {
            if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP) ||
                (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN))
            {
                if (m_recording)
                {
                    m_audioRecorder.stopRecording();
                    m_recording = false;
                    m_buttonTalk.setPressed( false );
                }
                return true;
            }
        }
        return super.onKeyDown( keyCode, event );
    }
}
