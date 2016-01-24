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
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class StateView extends View
{
    private final Paint [] m_paint;
    private int m_state;

    protected void onMeasure( int widthMeasureSpec, int heightMeasureSpec )
    {
        super.onMeasure( widthMeasureSpec, heightMeasureSpec );
        final int suggestedMinHeight = getSuggestedMinimumHeight();
        setMeasuredDimension( suggestedMinHeight, suggestedMinHeight );
    }

    protected void onDraw( Canvas canvas )
    {
        super.onDraw( canvas );

        if (m_state < m_paint.length)
        {
            final float cx = (getWidth() / 2);
            final float cy = (getHeight() / 2);
            final float cr = (cx - cx / 2f);
            canvas.drawCircle( cx, cy, cr, m_paint[m_state] );
        }
    }

    public StateView( Context context, AttributeSet attrs )
    {
        super( context, attrs );

        final TypedArray a = context.obtainStyledAttributes(
                attrs, new int [] { android.R.attr.minHeight }, android.R.attr.buttonStyle, 0 );
        if (a != null)
        {
            final int minHeight = a.getDimensionPixelSize( 0, -1 );
            if (minHeight != -1)
                setMinimumHeight( minHeight );
            a.recycle();
        }

        setWillNotDraw( false );

        m_paint = new Paint[2];

        m_paint[0] = new Paint();
        m_paint[0].setColor( Color.DKGRAY );

        m_paint[1] = new Paint();
        m_paint[1].setColor( Color.GREEN );
    }

    void setIndicatorState( int state )
    {
        if (state < m_paint.length)
        {
            if (m_state != state)
            {
                m_state = state;
                invalidate();
            }
        }
        else if (BuildConfig.DEBUG)
            throw new AssertionError();
    }
}
