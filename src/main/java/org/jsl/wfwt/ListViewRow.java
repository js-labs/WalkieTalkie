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

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

class ListViewRow extends LinearLayout
{
    private static final String LOG_TAG = ListViewRow.class.getSimpleName();

    private MainActivity m_activity;
    private TextView m_textViewStationName;
    private TextView m_textViewAddrAndPing;
    private StateView m_stateView;
    private int m_position;

    public ListViewRow(Context context)
    {
        super(context);
    }

    public ListViewRow(Context context, AttributeSet attrs)
    {
        super(context, attrs);
    }

    public ListViewRow(Context context, AttributeSet attrs, int defStyle)
    {
        super(context, attrs, defStyle);
    }

    public void setPressed( boolean pressed )
    {
        super.setPressed( pressed );
        Log.d( LOG_TAG, "setPressed: " + pressed );
        m_activity.onListViewItemPressed( m_position, pressed );
    }

    public void init( MainActivity activity )
    {
        m_activity = activity;
        m_textViewStationName = (TextView) findViewById( R.id.textViewStationName );
        m_textViewAddrAndPing = (TextView) findViewById( R.id.textViewAddrAndPing );
        m_stateView = (StateView) findViewById( R.id.stateView );
    }

    public void setData( int position, String stationName, String addAndPing, int indicatorState )
    {
        m_position = position;
        m_textViewStationName.setText( stationName );
        m_textViewAddrAndPing.setText( addAndPing );
        m_stateView.setIndicatorState( indicatorState );
    }
}
