package org.commacq.http;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.commacq.CsvLine;
import org.commacq.BlockCallback;
import org.commacq.CsvUpdateBlockException;
import org.commacq.layer.Layer;
import org.commacq.layer.SubscribeLayer;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

@Slf4j
@RequiredArgsConstructor
public class CsvWebSocketHandler extends WebSocketHandler {
	
	private final SubscribeLayer layer;

	@Override
	public void configure(WebSocketServletFactory webSocketServletFactory) {
		webSocketServletFactory.setCreator(new CsvWebSocketCreator());
	}
		
	private class CsvWebSocketCreator implements WebSocketCreator {
		@Override
		public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp) {
			String entityId = HttpUtils.getEntityStringFromTarget(req.getRequestURI().toString());
			log.info("Creating web socket for entity: {}", entityId);
			return new CsvWebSocket(layer, entityId);
		}
	}
	
	@Slf4j
	private static class CsvWebSocket implements WebSocketListener {
		private final SubscribeLayer layer;
		private final String entityId;
		
		private BlockCallback callback;
		
		public CsvWebSocket(SubscribeLayer layer, String entityId) {
			this.layer = layer;
			this.entityId = entityId;
		}

		@Override
		public void onWebSocketBinary(byte[] payload, int offset, int len) {
			log.warn("Not expecting to receive binary payload, which has length: {}", len);
		}

		@Override
		public void onWebSocketClose(int statusCode, String reason) {
			log.info("Closing WebSocket and unsubscribing");
			layer.unsubscribe(callback);
		}

		@Override
		public void onWebSocketConnect(Session session) {
			try {
				if(!layer.getEntityIds().contains(entityId)) {
				    String message = String.format("Unknown entity id: %s", entityId);
				    log.warn(message);
				    session.getRemote().sendString(message);
				    return;
				}
				callback = new CsvLineCallbackWebSocket(session, layer, entityId);
				callback.start(Collections.singleton(entityId));
				layer.getAllCsvLinesAndSubscribe(entityId, callback);
				callback.finish();
			} catch (IOException ex) {
				log.error("Could not send message to opened connection", ex);
			} catch (CsvUpdateBlockException ex) {
				log.error("Could not send message to opened connection", ex);
			}
		}

		@Override
		public void onWebSocketError(Throwable cause) {
			log.info("Closing WebSocket due to error");
			layer.unsubscribe(callback);
		}

		@Override
		public void onWebSocketText(String message) {
			log.warn("Not expecting to receive text: {}", message);	
		}
	}
	
	@Slf4j
	@RequiredArgsConstructor
	private static class CsvLineCallbackWebSocket implements BlockCallback {
		private final Session session;
		private final Layer layer;
		private final String entityId;
		
		private int rowCount = 0;

		//TODO Work out how to notify the client that a bulk update is underway.
		@Override
		public void startBulkUpdate(String entityId, String columnNamesCsv) throws CsvUpdateBlockException {
			log.error("Bulk updates not yet supported");
		}

		@Override
		public void startBulkUpdateForGroup(String entityId, String group, String idWithinGroup) throws CsvUpdateBlockException {
		}
		
		@Override
		public void start(Collection<String> entityIds) throws CsvUpdateBlockException {	
			if(rowCount != 0) {
				throw new RuntimeException(
						"startUpdateBlock should never be called before a previous " +
								"update has been finished or cancelled."
						);
			}
			try {
				session.getRemote().sendPartialString(layer.getColumnNamesCsv(entityId), false);
				session.getRemote().sendPartialString("\n", false);
			} catch (IOException ex) {
				throw new CsvUpdateBlockException(ex);
			}
		}
		
		@Override
		public void finish() throws CsvUpdateBlockException {
			try {
				log.info("Pushed {} entities to client", rowCount);
				rowCount = 0;
				session.getRemote().sendPartialString("", true);
			} catch (IOException ex) {
				throw new CsvUpdateBlockException(ex);
			}
		}

		@Override
		public void processUpdate(String entityId, String columnNamesCsv, CsvLine csvLine) throws CsvUpdateBlockException {
			try {
				session.getRemote().sendPartialString(csvLine.getCsvLine(), false);
				session.getRemote().sendPartialString("\n", false);
				rowCount++;
			} catch (IOException ex) {
				throw new CsvUpdateBlockException(ex);
			}
		}

		@Override
		public void processRemove(String entityId, String columnNamesCsv, String id) throws CsvUpdateBlockException {
			try {
				session.getRemote().sendPartialString(id, false);
				session.getRemote().sendPartialString("\n", false);
				rowCount++;
			} catch (IOException ex) {
				throw new CsvUpdateBlockException(ex);
			}
		}

		//TODO implement cancellation.
		@Override
		public void cancel() {
			log.error("Unable to handle cancellation; not yet implemented");
			rowCount = 0;
		}
		
		@Override
		public String toString() {
			return "CsvLineCallbackWebSocket: " + session.getRemoteAddress();
		}
		
	}
	
}