/*
 * Copyright (c) 2010 Jeff Boody
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
import java.util.LinkedList;
import android.content.res.Resources;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import android.content.Context;

public class A3DResource
{
	private static final String TAG = "A3DResource";

	private class A3DResourceItem
	{
		public int    Id;
		public String Tag;

		A3DResourceItem(int id, String tag)
		{
			Id  = id;
			Tag = tag;
		}
	}

	private Context Ctx;
	private int     Timestamp;
	private LinkedList<A3DResourceItem> Resource_List = new LinkedList<A3DResourceItem>();

	public A3DResource(Context ctx, int timestamp)
	{
		Ctx = ctx;
		Timestamp = timestamp;
	}

	public void Add(int id, String tag)
	{
		try
		{
			Resource_List.add(new A3DResourceItem(id, tag));
		}
		catch(Exception e)
		{
			Log.e(TAG, "exception: " + e);
		}
	}

	public void Update()
	{
		// initialize native resources
		if(IsTSValid() == 0)
		{
			for(int i = 0; i < Resource_List.size(); ++i)
			{
				A3DResourceItem r = Resource_List.get(i);
				CopyRes(r.Id, r.Tag);
			}
			CopyRes(Timestamp, "timestamp.raw");
		}
	}

	private void CopyRes(int src, String dst)
	{
		Log.i(TAG, "CopyRes " + dst);
		try
		{
			Resources r = Ctx.getResources();
			InputStream      stream1 = r.openRawResource(src);
			FileOutputStream stream2 = Ctx.openFileOutput(dst, Ctx.MODE_WORLD_WRITEABLE);
			byte[] buffer = new byte[4096];   // 4KB buffer
			int bytes_read;
			while((bytes_read = stream1.read(buffer, 0, 4096)) != -1)
			{
				stream2.write(buffer, 0, bytes_read);
			}
		}
		catch(Exception e)
		{
			Log.e(TAG, "exception: " + e);
		}
	}

	private int IsTSValid()
	{
		try
		{
			Resources r = Ctx.getResources();
			InputStream     stream1 = r.openRawResource(Timestamp);
			FileInputStream stream2 = Ctx.openFileInput("timestamp.raw");
			byte[] ts1 = new byte[32];   // 32 byte buffer
			byte[] ts2 = new byte[32];   // 32 byte buffer
			int sz1    = stream1.read(ts1, 0, 32);
			int sz2    = stream2.read(ts2, 0, 32);

			int status = 0;
			if(sz1 == sz2)
			{
				status = 1;
				for(int i = 0; i < sz1; ++i)
				{
					if(ts1[i] != ts2[i])
					{
						status = 0;
						break;
					}
				}
			}
			return status;
		}
		catch(Exception e)
		{
			return 0;
		}
	}
}
