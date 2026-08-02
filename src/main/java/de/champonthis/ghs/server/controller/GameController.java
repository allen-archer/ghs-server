package de.champonthis.ghs.server.controller;

import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.TextMessage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import de.champonthis.ghs.server.businesslogic.Manager;
import de.champonthis.ghs.server.model.GameCharacterModel;
import de.champonthis.ghs.server.model.GameModel;
import de.champonthis.ghs.server.model.GameState;
import de.champonthis.ghs.server.model.Identifier;
import de.champonthis.ghs.server.model.Permissions;
import de.champonthis.ghs.server.socket.MessageHandler;
import de.champonthis.ghs.server.socket.model.WebSocketSessionContainer;

@RestController
@RequestMapping("game")
@CrossOrigin(origins = "*")
public class GameController {

	private final Manager manager;
	private final Gson gson;
	private final MessageHandler messageHandler;
	private final boolean isPublic;
	private final boolean debug;

	public GameController(
			@Value("${ghs-server.public:false}") boolean isPublic,
			@Value("${ghs-server.debug:false}") boolean debug,
			Manager manager,
			Gson gson,
			MessageHandler messageHandler) {
		this.manager = manager;
		this.gson = gson;
		this.messageHandler = messageHandler;
		this.isPublic = isPublic;
		this.debug = debug;
	}

	protected GameModel getGame(String gameCode) {
		if (!StringUtils.hasText(gameCode)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}

		Long gameId = manager.getGameIdByGameCode(gameCode);

		if (gameId == null) {
			// if first game code or public create new game for game code
			if (manager.countGameCodes() == 0 || isPublic) {
				GameModel game = new GameModel();
				gameId = manager.createGame(game);
				manager.createGameCode(gameCode, gameId);
			} else {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN);
			}
		}

		GameModel game = manager.getGame(gameId);

		if (game == null) {
			throw new ResponseStatusException(HttpStatus.NO_CONTENT);
		}

		return game;
	}

	@GetMapping(produces = { MediaType.APPLICATION_JSON_VALUE })
	public String requestGame(@RequestHeader(name = HttpHeaders.AUTHORIZATION) String gameCode) {
		return gson.toJson(getGame(gameCode));
	}

	@PostMapping(produces = { MediaType.APPLICATION_JSON_VALUE })
	public String updateGame(@RequestHeader(name = HttpHeaders.AUTHORIZATION) String gameCode,
			@RequestParam Optional<Boolean> silent, @RequestBody String payload) {

		if (!StringUtils.hasText(gameCode)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		} else if (gameCode.length() > 1024) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "game code too long");
		}

		GameModel game = getGame(gameCode);

		Long gameId = manager.getGameIdByGameCode(gameCode);

		Permissions permissions = manager.getPermissionsByGameCode(gameCode);

		if (payload == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid game payload");
		}

		try {
			GameModel gameUpdate = null;
			JsonArray undoInfo = null;
			JsonObject data = JsonParser.parseString(payload).getAsJsonObject();

			if (data.has("game")) {
				gameUpdate = gson.fromJson(data.get("game"), GameModel.class);
				if (data.has("undoinfo")) {
					undoInfo = data.get("undoinfo").getAsJsonArray();
				}
			} else {
				gameUpdate = gson.fromJson(payload, GameModel.class);
			}

			if (gameUpdate == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid game payload");
			}

			if (Objects.equals(gameUpdate.getRevision(), game.getRevision())) {
				game.setPlaySeconds(gameUpdate.getPlaySeconds());
				gameUpdate = game;
			} else if (gameUpdate.getRevision() < game.getRevision()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"invalid revision: " + gameUpdate.getRevision());
			}

			String permissionViolation = manager.checkPermissions(game, gameUpdate, permissions);
			if (permissionViolation != null) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN, permissionViolation);
			}

			gameUpdate.setServer(false);
			manager.setGame(gameId, gameUpdate);

			if (silent.isEmpty() || !silent.get()) {
				for (WebSocketSessionContainer container : messageHandler.getWebSocketSessions()) {
					if (container.getGameId() == gameId
							&& !messageHandler.getWebSocketSessionsCleanUp().contains(container)) {
						JsonObject gameResponse = new JsonObject();
						if (!game.isServer()) {
							gameUpdate.setServer(messageHandler.isServerSession(container.getSession(), gameId));
						}
						gameResponse.addProperty("type", "game");
						gameResponse.add("payload", gson.toJsonTree(gameUpdate));
						if (undoInfo != null) {
							gameResponse.add("undoinfo", undoInfo);
						}
						container.getSession().sendMessage(new TextMessage(gson.toJson(gameResponse)));
					}
				}
			}

			return gson.toJson(gameUpdate);
		} catch (Exception e) {
			if (!(e instanceof ResponseStatusException)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid game payload");
			} else {
				throw (ResponseStatusException) e;
			}
		}
	}

	@PostMapping(value = "/initiative", produces = { MediaType.APPLICATION_JSON_VALUE })
	public String setInitiative(@RequestHeader(name = HttpHeaders.AUTHORIZATION) String gameCode,
			@RequestBody String payload) {

		GameModel game = getGame(gameCode);

		Long gameId = manager.getGameIdByGameCode(gameCode);

		Permissions permissions = manager.getPermissionsByGameCode(gameCode);

		if (payload == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid game payload");
		}

		JsonObject data = JsonParser.parseString(payload).getAsJsonObject();

		if (!data.has("playerNumber") || !data.has("initiative")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid game payload");
		}

		try {
			boolean changed = false;
			int playerNumber = data.get("playerNumber").getAsInt();
			int initiative = data.get("initiative").getAsInt();
			boolean longRest = initiative == 99 && data.has("longRest") && data.get("longRest").getAsBoolean();
			boolean force = data.has("force") && data.get("force").getAsBoolean();

			if (!force && game.getState().equals(GameState.NEXT)) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN,
						"Do not set Initiative after Draw!");
			}

			if (permissions != null) {
				if (!permissions.isCharacters()) {
					boolean characterPermission = false;
					for (GameCharacterModel character : game.getCharacters()) {
						if (character.getNumber() == playerNumber) {
							for (Identifier characterFigure : permissions.getCharacter()) {
								if (characterFigure.getName().equals(character.getName())
										&& characterFigure.getEdition().equals(character.getEdition())) {
									characterPermission = true;
									break;
								}
							}
						}
					}
					if (!characterPermission) {
						throw new ResponseStatusException(HttpStatus.FORBIDDEN,
								"Permission(s) missing: characters");
					}
				}
			}

			for (GameCharacterModel character : game.getCharacters()) {
				if (character.getNumber() == playerNumber
						&& (character.getInitiative() != initiative || character.isLongRest() != longRest)) {
					character.setInitiative(initiative);
					character.setLongRest(longRest);
					changed = true;
				}
			}

			if (!changed) {
				throw new ResponseStatusException(HttpStatus.NOT_MODIFIED);
			}

			game.setRevision(game.getRevision() + 1);
			game.setServer(false);
			manager.setGame(gameId, game);

			for (WebSocketSessionContainer container : messageHandler.getWebSocketSessions()) {
				if (container.getGameId() == gameId
						&& !messageHandler.getWebSocketSessionsCleanUp().contains(container)) {
					JsonObject gameResponse = new JsonObject();
					if (!game.isServer()) {
						game.setServer(messageHandler.isServerSession(container.getSession(), gameId));
					}
					gameResponse.addProperty("type", "game");
					gameResponse.add("payload", gson.toJsonTree(game));

					JsonArray undoInfo = new JsonArray();
					undoInfo.add("serverSync");
					undoInfo.add("setInitiative");
					undoInfo.add("#" + playerNumber);
					undoInfo.add(initiative);
					gameResponse.add("undoinfo", undoInfo);
					container.getSession().sendMessage(new TextMessage(gson.toJson(gameResponse)));
				}
			}

			return gson.toJson(game);
		} catch (Exception e) {
			if (!(e instanceof ResponseStatusException)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid game payload");
			} else {
				throw (ResponseStatusException) e;
			}
		}
	}

	@PostMapping(value = "/command")
	public void remoteCommand(@RequestHeader(name = HttpHeaders.AUTHORIZATION) String gameCode,
			@RequestBody String payload) {

		Long gameId = manager.getGameIdByGameCode(gameCode);

		if (gameId == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid authorization");
		}

		Permissions permissions = manager.getPermissionsByGameCode(gameCode);

		if (permissions != null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing permissions");
		}

		if (payload == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid game payload");
		}

		JsonObject data = JsonParser.parseString(payload).getAsJsonObject();

		if (!data.has("id")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid command payload");
		}

		for (WebSocketSessionContainer container : messageHandler.getWebSocketSessions()) {
			if (container.getGameId() == gameId
					&& !messageHandler.getWebSocketSessionsCleanUp().contains(container)) {
				try {
					JsonObject response = new JsonObject();
					response.addProperty("type", "remoteCommand");
					response.add("payload", data);
					container.getSession()
							.sendMessage(new TextMessage(gson.toJson(response)));
				} catch (Exception e) {
					if (debug) {
						e.printStackTrace();
					}
				}
			}
		}

	}
}
