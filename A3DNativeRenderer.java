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
import android.content.Context;
import android.content.res.Resources;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.Math;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;   // EGL_CONTEXT_LOST
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class A3DNativeRenderer implements A3DRenderer
{
	private static final String TAG = "A3DNativeRenderer";

	// timer stuff
	private long Prev_Draw = 0;
	private long Total_Draw = 0;
	private long Prev_eglSwapBuffers = 0;
	private long Total_eglSwapBuffers = 0;
	private long T0 = System.currentTimeMillis();

	// OpenGL ES State
	private EGL10 egl;
	private EGLDisplay Gfx_Display;
	private EGLSurface Gfx_Surface = EGL10.EGL_NO_SURFACE;
	private EGLConfig  Gfx_Config;
	private EGLContext Gfx_Context = EGL10.EGL_NO_CONTEXT;
	private boolean    Gfx_Context_Lost = false;
	private static int EGL_OPENGL_ES_BIT  = 1;
	private static int EGL_OPENGL_ES2_BIT = 4;
	private static int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

	int Width  = 320;
	int Height = 480;

	// Reference to the A3DSurfaceView SurfaceHolder
	private SurfaceHolder Surface_Holder;

	// Native interface
	private native void NativeCreate();
	private native void NativeDestroy();
	private native void NativeChangeSurface(int w, int h);
	private native void NativeResume();
	private native void NativePause();
	private native void NativeDraw();
	private native int  NativeClientVersion();

	private boolean Has_Created_Native = false;

	private int CheckEGLError(String s)
	{
		int error = egl.eglGetError();
		if     (error == EGL11.EGL_NOT_INITIALIZED)     Log.e(TAG, s + " EGL_NOT_INITIALIZED");
		else if(error == EGL11.EGL_BAD_ACCESS)          Log.e(TAG, s + " EGL_BAD_ACCESS");
		else if(error == EGL11.EGL_BAD_ALLOC)           Log.e(TAG, s + " EGL_BAD_ALLOC");
		else if(error == EGL11.EGL_BAD_ATTRIBUTE)       Log.e(TAG, s + " EGL_BAD_ATTRIBUTE");
		else if(error == EGL11.EGL_BAD_CONTEXT)         Log.e(TAG, s + " EGL_BAD_CONTEXT");
		else if(error == EGL11.EGL_BAD_CONFIG)          Log.e(TAG, s + " EGL_BAD_CONFIG");
		else if(error == EGL11.EGL_BAD_CURRENT_SURFACE) Log.e(TAG, s + " EGL_BAD_CURRENT_SURFACE");
		else if(error == EGL11.EGL_BAD_DISPLAY)         Log.e(TAG, s + " EGL_BAD_DISPLAY");
		else if(error == EGL11.EGL_BAD_SURFACE)         Log.e(TAG, s + " EGL_BAD_SURFACE");
		else if(error == EGL11.EGL_BAD_MATCH)           Log.e(TAG, s + " EGL_BAD_MATCH");
		else if(error == EGL11.EGL_BAD_PARAMETER)       Log.e(TAG, s + " EGL_BAD_PARAMETER");
		else if(error == EGL11.EGL_BAD_NATIVE_PIXMAP)   Log.e(TAG, s + " EGL_BAD_NATIVE_PIXMAP");
		else if(error == EGL11.EGL_BAD_NATIVE_WINDOW)   Log.e(TAG, s + " EGL_BAD_NATIVE_WINDOW");
		else if(error == EGL11.EGL_CONTEXT_LOST)        Log.e(TAG, s + " EGL_CONTEXT_LOST");

		return error;
	}

	// Renderer implementation
	public A3DNativeRenderer(Context context)
	{
	}

	public void CreateContext()
	{
		if(Gfx_Context != EGL10.EGL_NO_CONTEXT)
		{
			return;
		}

		int[] version    = new int[2];
		int[] num_config = new int[1];

		int client_version = NativeClientVersion();

		egl = (EGL10) EGLContext.getEGL();
		Gfx_Display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
		CheckEGLError("CreateContext eglGetDisplay");

		if(!egl.eglInitialize(Gfx_Display, version))
		{
			Log.e(TAG, "CreateContext - eglInitialize failed");
			return;
		}
		CheckEGLError("CreateContext eglInitialize");
		Log.i(TAG, "EGL version is " + version[0] + "." + version[1]);

		// Querry the configurations
		if(!egl.eglGetConfigs(Gfx_Display, null, 0, num_config))
		{
			Log.e(TAG, "CreateContext - eglGetConfigs could not determine number of configs");
			return;
		}
		CheckEGLError("CreateContext eglGetConfigs1");

		EGLConfig[] configs = new EGLConfig[num_config[0]];
		if(!egl.eglGetConfigs(Gfx_Display, configs, num_config[0], num_config))
		{
			Log.e(TAG, "CreateContext - eglGetConfigs could not determine number of configs");
			return;
		}
		CheckEGLError("CreateContext eglGetConfigs2");

		Gfx_Config = null;

		// Log the available configurations
		int[] color_buf_type  = new int[1];
		int[] surface_type    = new int[1];
		int[] renderable_type = new int[1];
		int[] red             = new int[1];
		int[] green           = new int[1];
		int[] blue            = new int[1];
		int[] alpha           = new int[1];
		int[] depth           = new int[1];
		int[] stencil         = new int[1];
		int[] sample_buffers  = new int[1];
		int[] samples         = new int[1];
		int[] id              = new int[1];
		int[] caveat          = new int[1];
		int selected = -1;
		for(int i = 0; i < num_config[0]; ++i)
		{
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_COLOR_BUFFER_TYPE, color_buf_type);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_SURFACE_TYPE, surface_type);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_RENDERABLE_TYPE, renderable_type);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_RED_SIZE, red);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_GREEN_SIZE, green);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_BLUE_SIZE, blue);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_ALPHA_SIZE, alpha);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_DEPTH_SIZE, depth);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_STENCIL_SIZE, stencil);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_SAMPLE_BUFFERS, sample_buffers);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_SAMPLES, samples);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_CONFIG_ID, id);
			egl.eglGetConfigAttrib(Gfx_Display, configs[i], EGL10.EGL_CONFIG_CAVEAT, caveat);
			CheckEGLError("CreateContext eglGetConfigAttrib");

			Log.i(TAG, i + ": id=" + id[0] +
			               " rgbads=" + red[0] + "," + green[0] + "," + blue[0] + "," + alpha[0] + "," + depth[0] + "," + stencil[0] +
			               " type=" + color_buf_type[0] + "," + surface_type[0] + "," + renderable_type[0] + "," + caveat[0] +
			               " msaa=" + sample_buffers[0] + "," + samples[0]);

			// Manually choose a configuration
			if((surface_type[0]   &  EGL10.EGL_WINDOW_BIT) == 0) continue;   // exact
			if(red[0]            !=  5) continue;   // exact
			if(green[0]          !=  6) continue;   // exact
			if(blue[0]           !=  5) continue;   // exact
			if(alpha[0]          !=  0) continue;   // exact
			if(depth[0]          <  16) continue;
			if(stencil[0]        <   0) continue;
			if(sample_buffers[0] !=  0) continue;   // exact
			if(samples[0]        <   0) continue;
			if(client_version == 1)
			{
				if((renderable_type[0] & EGL_OPENGL_ES_BIT) == 0) continue;   // exact
			}
			else if(client_version == 2)
			{
				if((renderable_type[0] & EGL_OPENGL_ES2_BIT) == 0) continue;   // exact
			}

			// Just take the first accepted config (for now ...)
			if(selected < 0)
			{
				selected = id[0];
				Gfx_Config = configs[i];
			}
		}

		if(Gfx_Config == null)
		{
			Log.e(TAG, "CreateContext - Could not select desired EGL config in " + num_config[0] + " configs");
			return;
		}
		else
		{
			Log.i(TAG, "CreateContext - Using config " + selected);
		}

		if(client_version == 1)
		{
			Gfx_Context = egl.eglCreateContext(Gfx_Display, Gfx_Config, EGL10.EGL_NO_CONTEXT, null);
		}
		else if(client_version == 2)
		{
			int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
			Gfx_Context = egl.eglCreateContext(Gfx_Display, Gfx_Config, EGL10.EGL_NO_CONTEXT, attrib_list);
		}
		CheckEGLError("CreateContext eglCreateContext");
		if(Gfx_Context == EGL10.EGL_NO_CONTEXT)
		{
			Log.e(TAG, "CreateContext - eglCreateContext failed");
			return;
		}

		Log.i(TAG, "EGL_VERSION     - " + egl.eglQueryString(Gfx_Display, EGL10.EGL_VERSION));
		Log.i(TAG, "EGL_EXTENSIONS  - " + egl.eglQueryString(Gfx_Display, EGL10.EGL_EXTENSIONS));
		// Log.i(TAG, "EGL_CLIENT_APIS - " + egl.eglQueryString(Gfx_Display, EGL10.EGL_CLIENT_APIS));
	}

	public void DestroyContext()
	{
		if(Gfx_Context == EGL10.EGL_NO_CONTEXT)
		{
			return;
		}

		NativeDestroy();
		Has_Created_Native = false;

		if(!egl.eglMakeCurrent(Gfx_Display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT))
		{
			Log.e(TAG, "DestroyContext - eglMakeCurrent failed");
		}

		if(!egl.eglDestroyContext(Gfx_Display, Gfx_Context))
		{
			Log.e(TAG, "DestroyContext - eglDestroyContext failed");
		}
		Gfx_Context = EGL10.EGL_NO_CONTEXT;

		if(!egl.eglTerminate(Gfx_Display))
		{
			Log.e(TAG, "DestroyContext - eglTerminate failed");
		}
	}

	public void CreateSurface(SurfaceHolder surface_holder)
	{
		if(Gfx_Surface != EGL10.EGL_NO_SURFACE)
		{
			return;
		}

		Surface_Holder = surface_holder;

		egl = (EGL10) EGLContext.getEGL();

		Gfx_Surface = egl.eglCreateWindowSurface(Gfx_Display, Gfx_Config, Surface_Holder, null);
		CheckEGLError("CreateSurface eglCreateWindowSurface");
		if(Gfx_Surface == EGL10.EGL_NO_SURFACE)
		{
			Log.e(TAG, "CreateSurface - eglCreateWindowSurface failed");
			return;
		}

		if(!egl.eglMakeCurrent(Gfx_Display, Gfx_Surface, Gfx_Surface, Gfx_Context))
		{
			CheckEGLError("CreateSurface eglMakeCurrent");
			Gfx_Context_Lost = true;
			return;
		}
		CheckEGLError("CreateSurface eglMakeCurrent");

		if(Has_Created_Native == false)
		{
			NativeCreate();
			Has_Created_Native = true;
		}
	}

	public void DestroySurface()
	{
		if(Gfx_Surface == EGL10.EGL_NO_SURFACE)
		{
			return;
		}

		if(!egl.eglMakeCurrent(Gfx_Display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT))
		{
			Log.e(TAG, "DestroySurface - eglMakeCurrent failed");
		}

		if(!egl.eglDestroySurface(Gfx_Display, Gfx_Surface))
		{
			Log.e(TAG, "DestroySurface - eglDestroySurface failed");
		}
		Gfx_Surface = EGL10.EGL_NO_SURFACE;
	}

	public void ChangeSurface(int format, int width, int height)
	{
		NativeChangeSurface(width, height);
		Width  = width;
		Height = height;
	}

	public void Resume()
	{
		NativeResume();
	}

	public void Pause()
	{
		NativePause();
	}

	public void Draw()
	{
		// Restore context after suspend event
		// EGL_CONTEXT_LOST can be detected at eglSwapBuffers, eglCopyBuffers and eglMakeCurrent
		if(Gfx_Context_Lost)
		{
			Gfx_Context_Lost = false;
			DestroySurface();
			DestroyContext();
			CreateContext();
			CreateSurface(Surface_Holder);
			NativeChangeSurface(Width, Height);
			if(Gfx_Context_Lost == true) return;
			Log.i(TAG, "Draw - Context restored");
		}

		Prev_Draw = System.currentTimeMillis();
		NativeDraw();
		Total_Draw += System.currentTimeMillis() - Prev_Draw;

		Prev_eglSwapBuffers = System.currentTimeMillis();
		if(!egl.eglSwapBuffers(Gfx_Display, Gfx_Surface))
		{
			CheckEGLError("Draw eglSwapBuffers");
			Gfx_Context_Lost = true;
		}
		Total_eglSwapBuffers += System.currentTimeMillis() - Prev_eglSwapBuffers;
		CheckEGLError("Draw");

		{
		   long t = System.currentTimeMillis();

			// Don't update fps every frame
			if (t - T0 >= 1000)
			{
				// Log.i(TAG, "Draw = " + Total_Draw + ", eglSwapBuffers = " + Total_eglSwapBuffers);
				Total_Draw = 0;
				Total_eglSwapBuffers = 0;
				T0 = t;
			}
		}
	}
}
