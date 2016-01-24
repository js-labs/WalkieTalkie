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
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.Button;


public class SwitchButton extends Button
{
    private static final int STATE_IDLE = 0;
    private static final int STATE_DOWN = 1;
    private static final int STATE_DRAGGING_LEFT = 2;
    private static final int STATE_DRAGGING_RIGHT = 3;
    private static final int STATE_DRAGGING_DOWN = 4;
    private static final int STATE_LOCKED = 5;

    private StateListener m_stateListener;
    private final Drawable m_defaultBackground;
    private final Drawable m_pressedBackground;
    private final int m_touchSlop;
    private int m_state;
    private float m_touchX;
    private float m_touchY;

    public interface StateListener
    {
        void onStateChanged( boolean state );
    }

    public SwitchButton( Context context )
    {
        this( context, null );
    }

    public SwitchButton( Context context, AttributeSet attrs )
    {
        this( context, attrs, android.R.attr.buttonStyle );
    }

    public SwitchButton( Context context, AttributeSet attrs, int defStyle )
    {
        super( context, attrs, defStyle );

        m_defaultBackground = getBackground();
        m_pressedBackground = new ColorDrawable( getResources().getColor(android.R.color.holo_red_dark) );

        final ViewConfiguration config = ViewConfiguration.get(context);
        m_touchSlop = config.getScaledTouchSlop();

        m_state = STATE_IDLE;
    }

    public void setStateListener( StateListener stateListener )
    {
        m_stateListener = stateListener;
    }

    protected void onDraw( Canvas canvas )
    {
        super.onDraw( canvas );
    }

    public boolean onTouchEvent( MotionEvent ev )
    {
        final int action = ev.getAction();
        switch (action)
        {
            case MotionEvent.ACTION_DOWN:
                if (isEnabled())
                {
                    if (m_state == STATE_IDLE)
                    {
                        setPressed( true );
                        setBackground( m_pressedBackground );
                        m_state = STATE_DOWN;
                        m_touchX = ev.getX();
                        m_touchY = ev.getY();
                        if (m_stateListener != null)
                            m_stateListener.onStateChanged( true );
                        return true;
                    }
                    else if (m_state == STATE_LOCKED)
                    {
                        m_state = STATE_DOWN;
                        m_touchX = ev.getX();
                        m_touchY = ev.getY();
                        return true;
                    }
                    else
                    {
                        if (BuildConfig.DEBUG)
                            throw new AssertionError();
                    }
                }
            break;

            case MotionEvent.ACTION_MOVE:
            {
                final float x = ev.getX();
                final float y = ev.getY();
                final float dx = (x - m_touchX);
                final float dy = (y - m_touchY);

                switch (m_state)
                {
                    case STATE_IDLE:
                    break;

                    case STATE_DOWN:
                        if ((Math.abs(dx) > m_touchSlop) ||
                            (Math.abs(dy) > m_touchSlop))
                        {
                            if (Math.abs(dx) > Math.abs(dy))
                            {
                                if (dx > 0.0)
                                    m_state = STATE_DRAGGING_RIGHT;
                                else if (dx < 0.0)
                                    m_state = STATE_DRAGGING_LEFT;

                                getParent().requestDisallowInterceptTouchEvent( true );
                                m_touchX = x;
                                m_touchY = y;
                            }
                        }
                    return true;

                    case STATE_DRAGGING_RIGHT:
                        if ((dx > -0.2) && (Math.abs(dx) > Math.abs(dy)))
                        {
                            m_touchX = x;
                            m_touchY = y;
                        }
                        else if (Math.abs(dx) < Math.abs(dy))
                        {
                            m_touchX = x;
                            m_touchY = y;
                            m_state = STATE_DRAGGING_DOWN;
                        }
                        else
                        {
                            getParent().requestDisallowInterceptTouchEvent( false );
                            m_state = STATE_IDLE;
                        }
                    return true;

                    case STATE_DRAGGING_LEFT:
                        if ((dx < 0.2) && (Math.abs(dx) > Math.abs(dy)))
                        {
                            m_touchX = x;
                            m_touchY = y;
                        }
                        else if (Math.abs(dx) < Math.abs(dy))
                        {
                            m_touchX = x;
                            m_touchY = y;
                            m_state = STATE_DRAGGING_DOWN;
                        }
                        else
                        {
                            getParent().requestDisallowInterceptTouchEvent( false );
                            m_state = STATE_IDLE;
                        }
                    return true;

                    case STATE_DRAGGING_DOWN:
                        if (Math.abs(dy) > Math.abs(dx))
                        {
                            m_touchX = x;
                            m_touchY = y;
                        }
                        else
                        {
                            getParent().requestDisallowInterceptTouchEvent( false );
                            m_state = STATE_IDLE;
                        }
                    return true;
                }
            }
            break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (m_state == STATE_DRAGGING_DOWN)
                {
                    /* Keep button pressed */
                    m_state = STATE_LOCKED;
                    getParent().requestDisallowInterceptTouchEvent( false );
                }
                else
                {
                    m_stateListener.onStateChanged( false );
                    setBackground( m_defaultBackground );
                    setPressed( false );

                    if (m_state != STATE_IDLE)
                    {
                        m_state = STATE_IDLE;
                        getParent().requestDisallowInterceptTouchEvent( false );
                    }
                }
            break;
        }
        return super.onTouchEvent( ev );
    }
}
