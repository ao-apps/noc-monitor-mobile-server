/*
 * noc-monitor-mobile-server - Server for mobile access to Monitoring.
 * Copyright (C) 2008-2012, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of noc-monitor-mobile-server.
 *
 * noc-monitor-mobile-server is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * noc-monitor-mobile-server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with noc-monitor-mobile-server.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aoindustries.noc.monitor.mobile.server;

import com.aoindustries.noc.monitor.common.AlertLevel;
import com.aoindustries.noc.monitor.common.NodeSnapshot;
import com.aoindustries.noc.monitor.common.RootNode;
import com.aoindustries.noc.monitor.common.Monitor;
import com.aoindustries.util.concurrent.ExecutorService;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Provides a simplified interface for mobile device protocols.
 *
 * TODO: Move to separate project and process, with its own blender.
 *
 * @author  AO Industries, Inc.
 */
public class MobileServer {

	private static final Logger logger = Logger.getLogger(MobileServer.class.getName());

	private static final int PORT = 4585;

	/*private static int getNodeCount(NodeSnapshot snapshot) {
		int total = 1;
		for(NodeSnapshot child : snapshot.getChildren()) total += getNodeCount(child);
		return total;
	}*/

	private static void writeNodeTree(DataOutputStream out, NodeSnapshot node) throws IOException {
		out.writeUTF(node.getLabel());
		AlertLevel alertLevel = node.getAlertLevel();
		switch(alertLevel) {
			case NONE      : out.writeByte(0); break;
			case LOW       : out.writeByte(1); break;
			case MEDIUM    : out.writeByte(2); break;
			case HIGH      : out.writeByte(3); break;
			case CRITICAL  : out.writeByte(4); break;
			case UNKNOWN   : out.writeByte(5); break;
			default        : throw new AssertionError("Unexpected value for alertLevel: "+alertLevel);
		}
		String alertMessage = node.getAlertMessage();
		if(alertMessage!=null && alertMessage.length()==0) alertMessage = null;
		if(alertMessage!=null) {
			out.writeBoolean(true);
			out.writeUTF(alertMessage);
		} else {
			out.writeBoolean(false);
		}
		out.writeBoolean(node.getAllowsChildren());
		List<NodeSnapshot> children = node.getChildren();
		int numChildren = children.size();
		if(numChildren>Short.MAX_VALUE) throw new IOException("Too many children for current protocol: "+numChildren);
		out.writeShort(numChildren);
		for(int c=0;c<numChildren;c++) writeNodeTree(out, children.get(c));
	}

	private final Monitor monitor;
	private final String localAddress;

	private final Object threadLock = new Object();
	private Thread thread;

	public MobileServer(Monitor monitor, String localAddress) {
		this.monitor = monitor;
		this.localAddress = localAddress;
	}

	public void start() {
		synchronized(threadLock) {
			if(thread==null) {
				thread = new Thread(
					new Runnable() {
						@Override
						public void run() {
							final ExecutorService executorService = ExecutorService.newInstance();
							try {
								final Thread currentThread = Thread.currentThread();
								while(!currentThread.isInterrupted()) {
									synchronized(threadLock) {
										if(currentThread!=thread) break;
									}
									try {
										ServerSocketFactory factory = SSLServerSocketFactory.getDefault();
										ServerSocket ss;
										if(localAddress==null) {
											ss = factory.createServerSocket(PORT, 50);
										} else {
											InetAddress address = InetAddress.getByName(localAddress);
											ss = factory.createServerSocket(PORT, 50, address);
										}
										try {
											while(!currentThread.isInterrupted()) {
												synchronized(threadLock) {
													if(currentThread!=thread) break;
												}
												final Socket socket = ss.accept();
												executorService.submitUnbounded(
													new Runnable() {
														@Override
														public void run() {
															try {
																try {
																	DataInputStream in = new DataInputStream(socket.getInputStream());
																	try {
																		// Check authentication
																		String username = in.readUTF();
																		String password = in.readUTF();
																		RootNode rootNode; // Will be null if not authenticated
																		try {
																			rootNode = monitor.login(Locale.getDefault(), username, password);
																		} catch(IOException err) {
																			logger.log(Level.SEVERE, null, err);
																			rootNode = null;
																		}
																		DataOutputStream out = new DataOutputStream(new GZIPOutputStream(socket.getOutputStream()));
																		try {
																			if(rootNode==null) {
																				// Authentication failed
																				out.writeBoolean(false);
																			} else {
																				// Authentication successful
																				out.writeBoolean(true);
																				// Write snapshot
																				NodeSnapshot snapshot = rootNode.getSnapshot();
																				//logger.log(Level.INFO, "RootNode snapshot has a total of "+getNodeCount(snapshot)+" nodes");
																				writeNodeTree(out, snapshot);
																			}
																		} finally {
																			out.close();
																		}
																	} finally {
																		in.close();
																	}
																} finally {
																	socket.close();
																}
															} catch(Exception err) {
																logger.log(Level.SEVERE, null, err);
															}
														}
													}
												);
											}
										} finally {
											ss.close();
										}
									} catch(ThreadDeath td) {
										throw td;
									} catch(Throwable t) {
										logger.log(Level.SEVERE, null, t);
										try {
											Thread.sleep(60000);
										} catch(InterruptedException err) {
											// Normal on stop
										}
									}
								}
							} finally {
								executorService.dispose();
							}
						}
					}
				);
				thread.start();
			}
		}
	}

	public void stop() {
		synchronized(threadLock) {
			if(thread != null) {
				thread.interrupt();
				thread = null;
			}
		}
	}
}
