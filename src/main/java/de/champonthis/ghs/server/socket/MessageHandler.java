package de.champonthis.ghs.server.socket;

import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.champonthis.ghs.server.businesslogic.Manager;
import de.champonthis.ghs.server.model.GameModel;
import de.champonthis.ghs.server.model.Identifier;
import de.champonthis.ghs.server.model.Party;
import de.champonthis.ghs.server.model.Permissions;
import de.champonthis.ghs.server.model.Settings;
import de.champonthis.ghs.server.socket.exception.SendErrorException;
import de.champonthis.ghs.server.socket.model.MessageType;
import de.champonthis.ghs.server.socket.model.WebSocketSessionContainer;
import lombok.Getter;

@Component
public class MessageHandler extends TextWebSocketHandler {

	@Getter
	private final List<WebSocketSessionContainer> webSocketSessions = Collections.synchronizedList(new LinkedList<>());
	@Getter
	private final List<WebSocketSessionContainer> webSocketSessionsCleanUp = Collections
			.synchronizedList(new LinkedList<>());

	private final Manager manager;
	private final Gson gson;
	private final ThreadPoolTaskScheduler threadPoolTaskScheduler;
	private ScheduledFuture<?> cleanUpSessionTask;

	private final String buildVersion;
	private final boolean isPublic;
	private final boolean debug;

	public MessageHandler(
			@Value("${build.version}") String buildVersion,
			@Value("${ghs-server.public:false}") boolean isPublic,
			@Value("${ghs-server.debug:false}") boolean debug,
			Manager manager,
			Gson gson,
			ThreadPoolTaskScheduler threadPoolTaskScheduler) {
		this.manager = manager;
		this.gson = gson;
		this.threadPoolTaskScheduler = threadPoolTaskScheduler;
		this.buildVersion = buildVersion;
		this.isPublic = isPublic;
		this.debug = debug;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		super.afterConnectionEstablished(session);
		webSocketSessions.add(new WebSocketSessionContainer(-1, session));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
		super.afterConnectionClosed(session, status);
		WebSocketSessionContainer webSocketSessionContainer = null;
		Iterator<WebSocketSessionContainer> findSession = webSocketSessions.iterator();
		while (findSession.hasNext()) {
			WebSocketSessionContainer container = findSession.next();
			if (container.getSession() == session) {
				webSocketSessionContainer = container;
				break;
			}
		}
		if (webSocketSessionContainer != null) {
			if (cleanUpSessionTask != null) {
				cleanUpSessionTask.cancel(false);
			}
			long gameId = webSocketSessionContainer.getGameId();
			webSocketSessionContainer.setGameId(-1);
			webSocketSessionsCleanUp.add(webSocketSessionContainer);
			if (gameId != -1) {
				Iterator<WebSocketSessionContainer> findOthers = webSocketSessions.iterator();
				while (findOthers.hasNext()) {
					try {
						WebSocketSessionContainer container = findOthers.next();
						if (webSocketSessionsCleanUp.indexOf(container) == -1 && container.getGameId() == gameId) {
							if (container.getSession() != session && container.getSession().isOpen()) {
								GameModel game = manager.getGame(gameId);
								if (game == null) {
									sendError(container.getSession(), "No game found for 'id=" + gameId + "'");
								} else {
									game.setServer(isServerSession(container.getSession(), gameId));
									JsonObject gameResponse = newMessage("game-update");
									gameResponse.add("payload", gson.toJsonTree(game));
									try {
										container.getSession().sendMessage(new TextMessage(gson.toJson(gameResponse)));
									} catch (IllegalStateException e) {
										// session closed between isOpen() check and send — ignore
									}
								}
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
						break;
					}
				}
			}

			if (cleanUpSessionTask == null || cleanUpSessionTask.isCancelled()) {
				try {
					cleanUpSessionTask = threadPoolTaskScheduler.scheduleWithFixedDelay(new CleanUpSessionsRunner(),
							Duration.ofMillis(120000));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	class CleanUpSessionsRunner implements Runnable {

		@Override
		public void run() {
			try {
				for (WebSocketSessionContainer container : webSocketSessionsCleanUp) {
					webSocketSessions.remove(container);
				}
				webSocketSessionsCleanUp.clear();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
		super.handleMessage(session, message);
		if (message instanceof TextMessage) {
			TextMessage textMessage = (TextMessage) message;

			try {
				JsonObject messageObject = JsonParser.parseString(textMessage.getPayload()).getAsJsonObject();

				if ((messageObject.get("password") == null || messageObject.get("password").isJsonNull())
						&& (messageObject.get("code") == null || messageObject.get("code").isJsonNull())) {
					sendError(session, "'code' missing");
				}

				String gameCode = messageObject.has("code") && !messageObject.get("code").isJsonNull()
						? messageObject.get("code").getAsString()
						: messageObject.get("password").getAsString();

				if (!StringUtils.hasText(gameCode)) {
					sendError(session, "empty 'game code'");
				} else if (gameCode.length() > 1024) {
					sendError(session, "game code too long!");
				}

				Long gameId = manager.getGameIdByGameCode(gameCode);

				if (gameId == null) {
					// if first game code or public create new game for game code
					if (manager.countGameCodes() == 0 || isPublic) {
						GameModel game = new GameModel();
						game.setRevision(-1L);
						if (messageObject.get("payload") != null && !messageObject.get("payload").isJsonNull()) {
							game = gson.fromJson(messageObject.get("payload"), GameModel.class);
						}
						gameId = manager.createGame(game);
						manager.createGameCode(gameCode, gameId);
					} else {
						sendError(session, "Invalid game code '" + gameCode + "'");
					}
				}

				if (gameId != null) {
					GameModel game = manager.getGame(gameId);

					if (game == null) {
						sendError(session, "No game found for 'id=" + gameId + "'");
					} else {

						if (game.getParty() == null) {
							game.setParty(new Party());
						}

						Permissions permissions = manager.getPermissionsByGameCode(gameCode);

						game.setServer(isServerSession(session, gameId));

						MessageType type = MessageType
								.valueOf(messageObject.get("type").getAsString().toUpperCase().replace("-", "_"));

						switch (type) {
							case ERROR:
								sendError(session, "cannot send errors to server!");
								break;
							case PING:
								JsonObject pingMessage = newMessage("ping");
								pingMessage.addProperty("message", "pong");
								session.sendMessage(new TextMessage(gson.toJson(pingMessage)));
								break;
							case GAME:
							case GAME_UNDO:
							case GAME_REDO:
								if (messageObject.get("payload") == null || messageObject.get("payload").isJsonNull()) {
									sendError(session, "'payload' missing");
								}

								GameModel gameUpdate = gson.fromJson(messageObject.get("payload"), GameModel.class);

								if (gameUpdate == null) {
									sendError(session, "invalid game payload");
								} else {
									if (Objects.equals(gameUpdate.getRevision(), game.getRevision())) {
										game.setPlaySeconds(gameUpdate.getPlaySeconds());
										gameUpdate = game;
									} else if (gameUpdate.getRevision() < game.getRevision()) {
										sendError(session, "invalid revision");
									}

									String permissionViolation = manager.checkPermissions(game, gameUpdate, permissions);
									if (permissionViolation != null) {
										sendError(session, permissionViolation);
									}

									gameUpdate.setServer(false);
									manager.setGame(gameId, gameUpdate);

									for (WebSocketSessionContainer container : webSocketSessions) {
										if (!container.getSession().getId().equals(session.getId())
												&& container.getGameId() == gameId
												&& !webSocketSessionsCleanUp.contains(container)) {
											if (!game.isServer()) {
												gameUpdate.setServer(isServerSession(container.getSession(), gameId));
											}
											JsonObject gameResponse = newMessage(
													type.toString().toLowerCase().replace('_', '-'));
											gameResponse.add("payload", gson.toJsonTree(gameUpdate));
											gameResponse.add("undoinfo", messageObject.get("undoinfo"));
											gameResponse.add("revision", messageObject.get("revision"));
											gameResponse.add("undolength", messageObject.get("undolength"));
											container.getSession()
													.sendMessage(new TextMessage(gson.toJson(gameResponse)));
										}
									}
								}
								break;
							case GAME_UPDATE:
								if (messageObject.get("payload") == null || messageObject.get("payload").isJsonNull()) {
									sendError(session, "'payload' missing");
								}

								GameModel updateGame = gson.fromJson(messageObject.get("payload"), GameModel.class);

								if (updateGame == null) {
									sendError(session, "invalid game payload");
								} else {

									game.setPlaySeconds(updateGame.getPlaySeconds());
									updateGame = game;

									updateGame.setServer(false);
									manager.setGame(gameId, updateGame);

									for (WebSocketSessionContainer container : webSocketSessions) {
										if (!container.getSession().getId().equals(session.getId())
												&& container.getGameId() == gameId
												&& !webSocketSessionsCleanUp.contains(container)) {
											if (!game.isServer()) {
												updateGame.setServer(isServerSession(container.getSession(), gameId));
											}
											JsonObject gameResponse = newMessage("game-update");
											gameResponse.add("payload", gson.toJsonTree(updateGame));
											container.getSession()
													.sendMessage(new TextMessage(gson.toJson(gameResponse)));
										}
									}
								}
								break;
							case PERMISSIONS:
								if (permissions != null) {
									sendError(session, "Cannot create permissions!");
								}

								if (messageObject.get("payload") == null || messageObject.get("payload").isJsonNull()) {
									sendError(session, "'payload' missing");
								}

								JsonElement payload = messageObject.get("payload");
								if (!payload.isJsonObject()
										|| !payload.getAsJsonObject().has("password")
												&& !payload.getAsJsonObject().has("code")
										|| !payload.getAsJsonObject().has("permissions")) {
									sendError(session, "invalid 'payload'");
								}

								String permissionGameCode = payload.getAsJsonObject().has("code")
										&& !payload.getAsJsonObject().get("code").isJsonNull()
												? payload.getAsJsonObject().get("code").getAsString()
												: payload.getAsJsonObject().get("password").getAsString();

								Long permissionGameId = manager.getGameIdByGameCode(permissionGameCode);

								if (permissionGameId != null && !permissionGameId.equals(gameId)
										|| permissionGameCode.equals(gameCode)) {
									sendError(session, "'game code' already in use");
								}

								if (permissionGameId != null
										&& manager.getPermissionsByGameCode(permissionGameCode) == null) {
									sendError(session, "cannot overwrite root permissions");
								}

								Permissions permissionPermissions = gson.fromJson(
										payload.getAsJsonObject().get("permissions"),
										Permissions.class);

								if (permissionPermissions.isCharacters()) {
									permissionPermissions.setCharacter(new LinkedList<Identifier>());
								}

								if (permissionPermissions.isMonsters()) {
									permissionPermissions.setMonster(new LinkedList<Identifier>());
								}

								manager.saveGameCode(permissionGameCode, gson.toJson(permissionPermissions), gameId);
								break;
							case REQUEST_GAME:
								game.setServer(isServerSession(session, gameId));

								JsonObject gameResponse = newMessage("game");
								gameResponse.add("payload", gson.toJsonTree(game));
								session.sendMessage(new TextMessage(gson.toJson(gameResponse)));

								JsonObject permissionsResponse = newMessage("permissions");
								permissionsResponse.add("payload", gson.toJsonTree(permissions));
								session.sendMessage(new TextMessage(gson.toJson(permissionsResponse)));

								if (!game.isServer()) {
									for (WebSocketSessionContainer container : webSocketSessions) {
										if (container.getGameId() == gameId
												&& webSocketSessionsCleanUp.indexOf(container) == -1) {
											JsonObject updateResponse = newMessage("requestUpdate");
											container.getSession()
													.sendMessage(new TextMessage(gson.toJson(updateResponse)));
											break;
										}
									}
								}

								break;
							case REQUEST_SETTINGS:
								Settings settings = manager.getSettings(gameId);
								JsonObject settingsRequestResponse = newMessage("settings");

								// migration
								if (settings == null && !messageObject.has("allow-empty")) {
									settings = new Settings();
									manager.createSettings(settings, gameId);
								}

								if (settings != null) {
									settingsRequestResponse.add("payload", gson.toJsonTree(settings));
								}
								session.sendMessage(new TextMessage(gson.toJson(settingsRequestResponse)));
								break;
							case SETTINGS:
								if (messageObject.get("payload") == null || messageObject.get("payload").isJsonNull()) {
									sendError(session, "'payload' missing");
								}

								Settings settingsUpdate = gson.fromJson(messageObject.get("payload"), Settings.class);

								if (settingsUpdate == null) {
									sendError(session, "invalid settings payload");
								}

								if (manager.getSettings(gameId) == null) {
									manager.createSettings(settingsUpdate, gameId);
								} else {
									manager.setSettings(settingsUpdate, gameId);
								}

								for (WebSocketSessionContainer container : webSocketSessions) {
									if (container.getSession() != session && container.getGameId() == gameId
											&& !webSocketSessionsCleanUp.contains(container)) {
										JsonObject settingsResponse = newMessage("settings");
										settingsResponse.add("payload", gson.toJsonTree(settingsUpdate));
										container.getSession()
												.sendMessage(new TextMessage(gson.toJson(settingsResponse)));
									}
								}
								break;
							default:
								sendError(session, "invalid message type");
								break;
						}
					}
				}
			} catch (Exception e) {
				if (!(e instanceof SendErrorException)) {
					sendError(session, e.getMessage(), false);
					if (debug) {
						e.printStackTrace();
					}
				}
			}
		}

	}

	public boolean isServerSession(WebSocketSession session, Long gameId) {
		long serverSessionIndex = Long.MAX_VALUE;
		boolean isServer = false;
		for (WebSocketSessionContainer container : webSocketSessions) {
			if (!webSocketSessionsCleanUp.contains(container)) {
				int containerIndex = webSocketSessions.indexOf(container);
				if (container.getSession() == session) {
					if (containerIndex < serverSessionIndex) {
						serverSessionIndex = containerIndex;
						isServer = true;
					}
					container.setGameId(gameId);
					break;
				} else if (container.getGameId() == gameId && containerIndex < serverSessionIndex) {
					serverSessionIndex = containerIndex;
				}
			}
		}
		return isServer;
	}

	private JsonObject newMessage(String type) {
		JsonObject message = new JsonObject();
		message.addProperty("type", type);
		message.addProperty("serverVersion", buildVersion);
		return message;
	}

	protected void sendError(WebSocketSession session, String message) throws Exception {
		sendError(session, message, true);
	}

	protected void sendError(WebSocketSession session, String message, boolean stop) throws Exception {
		JsonObject error = newMessage("error");
		error.addProperty("message", message);
		session.sendMessage(new TextMessage(gson.toJson(error)));
		if (stop) {
			throw new SendErrorException("Error: " + message);
		}
	}

}
