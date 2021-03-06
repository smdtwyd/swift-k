/*
 * Swift Parallel Scripting Language (http://swift-lang.org)
 * Code from Java CoG Kit Project (see notice below) with modifications.
 *
 * Copyright 2005-2014 University of Chicago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//----------------------------------------------------------------------
//This code is developed as part of the Java CoG Kit project
//The terms of the license can be found at http://www.cogkit.org/license
//This message may not be removed or altered.
//----------------------------------------------------------------------

/*
 * Created on Oct 30, 2009
 */
package org.globus.cog.coaster.channels;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;
import org.globus.cog.coaster.RequestManager;
import org.globus.cog.coaster.UserContext;

/**
 * A channel implementation for which the other endpoint lives in the
 * same JVM.
 * 
 * @author Mihael Hategan
 *
 */
public class AbstractPipedChannel extends AbstractCoasterChannel {
	public static final Logger logger = Logger.getLogger(AbstractPipedChannel.class);

	private AbstractPipedChannel s;
	private final Sender sender;

	public AbstractPipedChannel(RequestManager requestManager, UserContext userContext, boolean client) {
		super(requestManager, userContext, client);
		sender = new Sender();
	}
	
	protected void setOther(AbstractPipedChannel s) {
		this.s = s;
	}

	protected void configureHeartBeat() {
		// no heart beat for these
	}

	@Override
	public void configureTimeoutChecks() {
		// no timeouts either
	}

	public boolean isOffline() {
		return false;
	}

	public boolean isStarted() {
		return true;
	}

	public void sendTaggedData(int tag, int flags, byte[] bytes, SendCallback cb) {
		try {
			sender.enqueue(new SendEntry(tag, flags, bytes, this, cb));
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	public void actualSend(int tag, int flags, byte[] bytes, SendCallback cb) {
		if (s == null) {
			throw new IllegalStateException("No endpoint set");
		}
		boolean fin = (flags & FINAL_FLAG) != 0;
		boolean error = (flags & ERROR_FLAG) != 0;
		boolean reply = (flags & REPLY_FLAG) != 0;
		// must make a copy of the buffer since system assumes that
		// buffer is reusable after being sent
		byte[] copy = new byte[bytes.length];
		System.arraycopy(bytes, 0, copy, 0, bytes.length);
		if (reply) {
			s.handleReply(tag, flags, copy.length, copy);
		}
		else {
			s.handleRequest(tag, flags, copy.length, copy);
		}
		if (cb != null) {
			cb.dataSent();
		}
	}

	private static class Sender extends Thread {
		public static final Logger logger = Logger.getLogger(Sender.class);

		private BlockingQueue<SendEntry> queue;
		private boolean dead;

		public Sender() {
			super("Piped Channel Sender");
			setDaemon(true);
			queue = new LinkedBlockingQueue<SendEntry>();
			start();
		}

		public void enqueue(SendEntry e) throws InterruptedException {
			queue.put(e);
		}

		public void run() {
			try {
				while (true) {
					SendEntry se = queue.take();
					try {
						((AbstractPipedChannel) se.channel).actualSend(se.tag, se.flags, se.data,
								se.cb);
					}
					catch (Exception e) {
						logger.warn("Got exception in send", e);
					}
				}
			}
			catch (InterruptedException e) {
				logger.warn("Interrupted", e);
			}
		}
	}

	private static class SendEntry {
		public final int tag, flags;
		public final byte[] data;
		public final AbstractCoasterChannel channel;
		public final SendCallback cb;

		public SendEntry(int tag, int flags, byte[] data, AbstractCoasterChannel channel,
				SendCallback cb) {
			this.tag = tag;
			this.flags = flags;
			this.data = data;
			this.channel = channel;
			this.cb = cb;
		}
	}

	@Override
	public void start() throws ChannelException {
	    super.start();
	}
}
