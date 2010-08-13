// ----------------------------------------------------------------------
// This code is developed as part of the Java CoG Kit project
// The terms of the license can be found at http://www.cogkit.org/license
// This message may not be removed or altered.
// ----------------------------------------------------------------------

/*
 * Created on Jun 6, 2003
 *
 */
package org.globus.cog.karajan.workflow.events;

import java.util.Collection;
import java.util.Iterator;

import org.apache.log4j.Logger;
import org.globus.cog.karajan.stack.VariableStack;
import org.globus.cog.karajan.workflow.ExecutionException;
import org.globus.cog.karajan.workflow.KarajanRuntimeException;
import org.globus.cog.karajan.workflow.nodes.FlowElement;

import edu.emory.mathcs.backport.java.util.concurrent.BlockingQueue;
import edu.emory.mathcs.backport.java.util.concurrent.LinkedBlockingQueue;
import edu.emory.mathcs.backport.java.util.concurrent.ThreadPoolExecutor;
import edu.emory.mathcs.backport.java.util.concurrent.TimeUnit;

/**
 * <p>Lightweight threading in Karajan is implemented using events.
 * The major events are {@link ControlEvent control events}, which are
 * sent to elements in order to command a certain execution behavior, and 
 * {@link NotificationEvent notification events} which are used to
 * report on the status of the execution of elements.</p>
 * 
 * <p>Events are sent to element using an event bus, which in turn uses
 * a fixed number of worker threads to do the actual dispatch. This is 
 * needed since most elements do their actual work as part of the event
 * handling code. As such, the <code>EventBus</code> can be seen
 * as a form of {@link java.util.concurrent.ExecutorService}.<p>
 * 
 * @author Mihael Hategan
 *
 */
public final class EventBus {
	public static final Logger logger = Logger.getLogger(EventBus.class);

	public static final int DEFAULT_WORKER_COUNT = Runtime.getRuntime().availableProcessors() * 4;

	public static final boolean TRACE_EVENTS = false;

	private static final EventBus bus = new EventBus();
	public volatile static long eventCount;
	public volatile static long cummulativeEventTime;

	private static EventHook hook;

	private final ThreadPoolExecutor es;
    private final BlockingQueue bq, sbq;
    private boolean suspended;
    public static volatile long eventsDispatched;

	public EventBus() {
		bq = new LinkedBlockingQueue();
		sbq = new LinkedBlockingQueue();
        es = new ThreadPoolExecutor(DEFAULT_WORKER_COUNT / 4, DEFAULT_WORKER_COUNT, 10, TimeUnit.SECONDS, bq);
	}

	private void _post(EventListener target, Event event) {
		EventTargetPair etp = new EventTargetPair(event, target);
		if (suspended) {
		    sbq.offer(etp);
		}
		else {
			es.submit(etp);
		}
	}

	private void _suspendAll() {
		suspended = true;
	}

	private void _resumeAll() {
	    suspended = false;
		Iterator i = sbq.iterator();
		while (i.hasNext()) {
		    es.submit((Runnable) i.next());
		}
		sbq.clear();
	}

	/**
	 * Post an event to the bus. The event will be placed in a queue
	 * and delivered in FIFO order using available worker threads.
	 */
	public static void post(EventListener target, Event event) {
		eventCount++;
		bus._post(target, event);
	}

	public static EventBus getBus() {
		return bus;
	}

	public synchronized static void initialize() {
	}

	/**
	 * Halts the dispatching of events. This is used to suspend the
	 * execution of a script. However, it is not sufficient to call
	 * this method, since some things may be running without sending
	 * or responding to events (for example a remote job).
	 */
	public static void suspendAll() {
		bus._suspendAll();
	}

	/**
	 * Resumes dispatching of events if previously stopped using
	 * {@link suspendAll}.
	 */
	public static void resumeAll() {
		bus._resumeAll();
	}

	public static boolean isInitialized() {
		return true;
	}

	public static void setEventHook(EventHook hook) {
		EventBus.hook = hook;
	}

	public static void removeEventHook() {
		EventBus.hook = null;
	}

	/**
	 * Send an event directly to the destination while also passing it
	 * through the hook if one is defined.
	 */
	public static void sendHooked(final EventListener l, final Event e) {
		if (hook == null) {
			send(l, e);
		}
		else {
			EventHook h = hook;
			if (h != null) {
				try {
					hook.event(l, e);
				}
				catch (Exception ex) {
					logger.warn("Bogus hook (" + hook.getClass().getName()
							+ ") threw exception. Bypassing.", ex);
					send(l, e);
				}
			}
			else {
				send(l, e);
			}
		}
	}

	/**
	 * Directly send an event to a destination. Typically the worker threads
	 * would use this method rather than it being used directly.
	 */
	public static void send(final EventListener l, final Event event) {
		try {
			if (TRACE_EVENTS) {
				logger.debug(event + " -> " + l);
			}
			if (l != null) {
				l.event(event);
			}
			else {
				logger.warn("Got event with no destination: " + event);
			}
		}
		catch (ExecutionException e) {
			if (logger.isInfoEnabled()) {
				logger.info("Caught execution exception", e);
				logger.info("Near Karajan line: " + l);
			}
			try {
				if (event instanceof FlowEvent) {
					failElement(l, (FlowEvent) event, e.getMessage());
				}
				else {
					logger.warn("ExecutionException caught, but event is not a flow event");
				}
			}
			catch (Exception ee) {
				logger.fatal("Cannot fail element", ee);
				logger.fatal("Exception was", e);
			}
		}
		catch (Throwable e) {
			try {
				logger.warn("Uncaught exception: " + e.toString() + " in " + l, e);
				logger.warn("Event was " + event);
				logger.warn("Exception is: " + e);
				logger.warn("Near Karajan line: " + l);
				try {
					if (event instanceof FlowEvent) {
						failElement(l, (FlowEvent) event, "Uncaught exception: " + e.toString());
					}
				}
				catch (Exception ee) {
					logger.fatal("Cannot fail element", ee);
				}
			}
			catch (Throwable ee) {
				logger.warn("Another uncaught exception while handling an uncaught exception.", ee);
				logger.warn("The initial exception was", e);
				try {
					if (event instanceof FlowEvent) {
						// this try time to fail the sender of the event
						failElement(null, (FlowEvent) event, "Double uncaught exception: "
								+ e.toString() + "\n" + ee.toString());
					}
				}
				catch (Throwable eee) {
					logger.fatal("Cannot fail element", ee);
				}
			}
		}
	}

	public static void failElement(EventListener l, FlowEvent event, String message)
			throws ExecutionException {
		VariableStack stack = event.getStack().copy();
		FlowElement fe;
		if (l instanceof FlowElement) {
			fe = (FlowElement) l;
		}
		else {
			fe = event.getFlowElement();
		}
		fe.failImmediately(stack, message);
	}

	/**
	 * Wait until all (atomic) executing elements have finished execution.
	 * In other words, wait until the state of the execution of a script
	 * is properly suspended. 
	 */
	public static boolean waitForEvents() {
		boolean busy = true;
		int count = 0;
		while (busy) {
			if (!bus.suspended) {
				throw new KarajanRuntimeException(
						"EventBus.waitForEvents() called with an unsuspended bus. Call EventBus.suspendAll() first");
			}
			if (bus.bq.isEmpty() && bus.es.getActiveCount() == 0) {
				busy = false;
			}
			if (busy) {
				if (count == 20) {
					logger.warn("Waited one second for events to be processed. Still busy");
				}
				if (count == 100) {
					logger.warn("Waited five second for events to be processed. Still busy");
				}
				if (count == 200) {
					logger.warn("Waited ten seconds for events to be processed. Still busy. Failing");
					return false;
				}
				try {
					Thread.sleep(50);
				}
				catch (InterruptedException e) {
				}
			}
			count++;
		}
		return true;
	}

	public static Collection getAllEvents() {
		if (!bus.suspended) {
			throw new KarajanRuntimeException(
					"EventBus.getAllEvents() called with an unsuspended bus. Call EventBus.suspendAll() first");
		}
		return bus.sbq;
	}
}