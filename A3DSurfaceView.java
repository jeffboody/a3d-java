/*
 * Copyright (c) 2009-2010 Jeff Boody
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package com.jeffboody.a3d;

import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.content.Context;
import android.util.AttributeSet;
import android.graphics.PixelFormat;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

interface A3DRenderer
{
	public void CreateSurface(SurfaceHolder surface_holder);
	public void DestroySurface();
	public void ChangeSurface(int format, int width, int height);
	public void Draw();
}

public class A3DSurfaceView extends SurfaceView implements Runnable, SurfaceHolder.Callback
{
	private static final String TAG = "A3DSurfaceView";
	private A3DRenderer Renderer;
	private SurfaceHolder Surface_Holder;
	private A3DResource Native_Resources;

	// Render thread state
	private Thread Render_Thread;
	boolean Stop_Renderer        = false;   // Has the renderer stopped? (construct a new renderer to draw again)
	private boolean Running_Flag = false;   // Is the rendering thread paused?
	private boolean Surface_Flag = false;   // Does the rendering thread have a surface?
	private Lock      Event_Lock = new ReentrantLock();
	private Condition Event_Cond = Event_Lock.newCondition();

	private class A3DEvent
	{
		// Wrap the flag so that it may be passed by reference
		// True indicates that event has occured
		public boolean Flag = false;
	}

	private class A3DSurfaceChangeEvent extends A3DEvent
	{
		public int Format = 0;
		public int Width  = 0;
		public int Height = 0;
	}

	// Events and event data
	private A3DEvent Pause_Event                        = new A3DEvent();
	private A3DEvent Resume_Event                       = new A3DEvent();
	private A3DEvent Stop_Event                         = new A3DEvent();
	private A3DEvent Surface_Created_Event              = new A3DEvent();
	private A3DEvent Surface_Destroyed_Event            = new A3DEvent();
	private A3DSurfaceChangeEvent Surface_Changed_Event = new A3DSurfaceChangeEvent();

	public A3DSurfaceView(A3DRenderer renderer, A3DResource r, Context context, AttributeSet attrs)
	{
        super(context, attrs);
		Init(renderer, r);
	}

	public A3DSurfaceView(A3DRenderer renderer, A3DResource r, Context context)
	{
		super(context);
		Init(renderer, r);
	}

	private void Init(A3DRenderer renderer, A3DResource r)
	{
		Log.i(TAG, "Init");

		Renderer = renderer;

		// null if no native resources used
		Native_Resources = r;

		// Enable touch screen
		// TODO - Was this necessary?
		setFocusableInTouchMode(true);

		// Start up the rendering thread
		Render_Thread = new Thread(this);
		Render_Thread.start();

		// Set up the SurfaceHolder.Callback interface
		Surface_Holder = getHolder();
		Surface_Holder.addCallback(this);
		Surface_Holder.setType(SurfaceHolder.SURFACE_TYPE_GPU);
		Surface_Holder.setFormat(PixelFormat.RGB_565);
	}

	/***********************************************************
	* UI interface to pause and resume the render thread       *
	***********************************************************/

	public void PauseRenderer()
	{
		Log.i(TAG, "PauseRenderer");
		QueueEventBlocking(Pause_Event);
	}

	public void ResumeRenderer()
	{
		Log.i(TAG, "ResumeRenderer");
		QueueEvent(Resume_Event);
	}

	public void StopRenderer()
	{
		Log.i(TAG, "StopRenderer");
		QueueEventBlocking(Stop_Event);
	}

	/***********************************************************
	* SurfaceHolder.Callback interface                         *
	***********************************************************/

    public void surfaceCreated(SurfaceHolder holder)
	{
		Log.i(TAG, "surfaceCreated");
		QueueEvent(Surface_Created_Event);
    }

    public void surfaceDestroyed(SurfaceHolder holder)
	{
		Log.i(TAG, "surfaceDestroyed");
		QueueEventBlocking(Surface_Destroyed_Event);
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h)
	{
		Log.i(TAG, "surfaceChanged " + w + "x" + h);
		Surface_Changed_Event.Format = format;
		Surface_Changed_Event.Width  = w;
		Surface_Changed_Event.Height = h;
		QueueEvent(Surface_Changed_Event);
    }

	/***********************************************************
	* UI thread queueing interface                             *
	***********************************************************/
	private void QueueEventBlocking(A3DEvent event)
	{
		Event_Lock.lock();
		try
		{
			// Don't handle any more events once stopped
			// finally automatically unlocks
			if(Stop_Renderer) return;

			event.Flag = true;

			// Wait for the render thread to handle the event
			while(event.Flag)
			{
				Event_Cond.signal();
				try { Event_Cond.await(); }
				catch(InterruptedException e) { }
			}
		}
		finally
		{
			Event_Lock.unlock();
		}
	}

	private void QueueEvent(A3DEvent event)
	{
		Event_Lock.lock();
		try
		{
			// Don't handle any more events once stopped
			// finally automatically unlocks
			if(Stop_Renderer) return;

			event.Flag = true;
			Event_Cond.signal();
		}
		finally
		{
			Event_Lock.unlock();
		}
	}

	/***********************************************************
	* Render thread interface                                  *
	***********************************************************/

	private boolean HandleEvents()
	{
		boolean needs_signal = false;

		Event_Lock.lock();
		try
		{
			// Receive events until all events have been handled and we are in a running state with an Android surface
			while(Resume_Event.Flag || Pause_Event.Flag || Stop_Event.Flag ||
			      Surface_Created_Event.Flag || Surface_Destroyed_Event.Flag || Surface_Changed_Event.Flag ||
			      !Running_Flag || !Surface_Flag)
			{
				if(DequeueEvent(Resume_Event))
				{
					Running_Flag = true;
				}

				if(DequeueEvent(Surface_Created_Event))
				{
					Renderer.CreateSurface(Surface_Holder);
					Surface_Flag = true;
				}

				if(DequeueEvent(Surface_Changed_Event))
				{
					Renderer.ChangeSurface(Surface_Changed_Event.Format, Surface_Changed_Event.Width, Surface_Changed_Event.Height);
				}

				if(DequeueEvent(Pause_Event))
				{
					Running_Flag = false;
					needs_signal = true;
				}

				if(DequeueEvent(Surface_Destroyed_Event))
				{
					Renderer.DestroySurface();
					Surface_Flag = false;
					needs_signal = true;
				}

				if(DequeueEvent(Stop_Event))
				{
					// Make sure we have paused and destroyed the surfaces first
					Running_Flag = false;
					if(Surface_Flag)
					{
						Renderer.DestroySurface();
						Surface_Flag = false;
					}
					Stop_Renderer = true;
					needs_signal = true;
				}

				// Notify UI thread that event was handled for blocking events
				if(needs_signal) Event_Cond.signal();

				// Exit the render thread when Stop_Event is received
				// Lock will be released by "finally" block before returning
				if(Stop_Renderer) return false;

				// Wait for events until we are "running" and have an Android surface
				if(!Running_Flag || !Surface_Flag)
				{
					try { Event_Cond.await(); }
					catch(InterruptedException e) { }
				}
			}
		}
		finally
		{
			Event_Lock.unlock();
		}

		// Render next frame
		return true;
	}

	private boolean DequeueEvent(A3DEvent event)
	{
		// No need for locking since DequeueEvent is only performed
		// by HandleEvents function which already performs locking
		if(event.Flag)
		{
			event.Flag = false;
			return true;
		}

		return false;
	}

	public void run()
	{
		if(Native_Resources != null)
			Native_Resources.Update();

		while(HandleEvents())
			Renderer.Draw();
	}
}
