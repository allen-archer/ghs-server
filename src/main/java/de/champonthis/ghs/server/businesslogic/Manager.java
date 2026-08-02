package de.champonthis.ghs.server.businesslogic;

import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.google.gson.Gson;

import de.champonthis.ghs.server.entity.Game;
import de.champonthis.ghs.server.entity.GameCode;
import de.champonthis.ghs.server.entity.Setting;
import de.champonthis.ghs.server.model.GameCharacterModel;
import de.champonthis.ghs.server.model.GameModel;
import de.champonthis.ghs.server.model.GameMonsterModel;
import de.champonthis.ghs.server.model.Identifier;
import de.champonthis.ghs.server.model.Permissions;
import de.champonthis.ghs.server.model.Settings;
import de.champonthis.ghs.server.repository.GameCodeRepository;
import de.champonthis.ghs.server.repository.GameRepository;
import de.champonthis.ghs.server.repository.SettingRepository;

@Component
public class Manager implements SmartInitializingSingleton {

	private final Gson gson;
	private final GameRepository gameRepository;
	private final GameCodeRepository gameCodeRepository;
	private final SettingRepository settingRepository;
	private final boolean gameCodesDump;

	public Manager(
			@Value("${ghs-server.gameCodesDump:true}") boolean gameCodesDump,
			Gson gson,
			GameRepository gameRepository,
			GameCodeRepository gameCodeRepository,
			SettingRepository settingRepository) {
		this.gson = gson;
		this.gameRepository = gameRepository;
		this.gameCodeRepository = gameCodeRepository;
		this.settingRepository = settingRepository;
		this.gameCodesDump = gameCodesDump;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (gameCodesDump) {
			List<GameCode> gameCodes = gameCodes();
			if (gameCodes != null) {
				for (GameCode gameCode : gameCodes) {
					String code = gameCode.getGameCode();
					String jsonPath = gameCode.getJsonPath();
					if (jsonPath == null) {
						jsonPath = "[ALL]";
					}
					Long gameId = gameCode.getGameId();

					boolean game = gameRepository.existsById(gameId);
					boolean settings = settingRepository.existsById(gameId);

					if (!game) {
						System.err.println("NO GAME for GAMECODE '" + code + "' found!");
					} else {
						System.out.println(
								"\nGAMECODE '" + code + "' GRANTING '" + jsonPath + "' ON GAME '" + gameId
										+ (settings ? "' WITH SETTINGS" : "'") + "\n");
					}
				}
			}
		}
	}

	public List<GameCode> gameCodes() {
		try {
			return gameCodeRepository.findAll();
		} catch (Exception e) {
			System.err.println(e.getMessage());
		}
		return null;
	}

	public long countGameCodes() {
		return gameCodeRepository.count();
	}

	public void createGameCode(String code, long gameId) {
		GameCode gameCode = new GameCode();
		gameCode.setGameCode(code);
		gameCode.setGameId(gameId);
		gameCodeRepository.save(gameCode);
	}

	public void saveGameCode(String code, String permissions, long gameId) {
		GameCode gameCode = new GameCode();
		gameCode.setGameCode(code);
		gameCode.setJsonPath(permissions);
		gameCode.setGameId(gameId);
		gameCodeRepository.save(gameCode);
	}

	/**
	 * Resolves the game id for {@code code}, creating a new game and game code for it
	 * if none exists yet and creation is allowed (first game code ever, or public server).
	 * Returns {@code null} if the code is unknown and creation is not allowed.
	 */
	public Long getOrCreateGameId(String code, boolean isPublic, GameModel newGame) {
		Long gameId = getGameIdByGameCode(code);

		if (gameId == null && (countGameCodes() == 0 || isPublic)) {
			gameId = createGame(newGame);
			createGameCode(code, gameId);
		}

		return gameId;
	}

	public Long getGameIdByGameCode(String code) {
		GameCode gameCode = gameCodeRepository.findById(code).orElse(null);

		if (gameCode != null) {
			return gameCode.getGameId();
		}

		return null;
	}

	public Permissions getPermissionsByGameCode(String code) {
		GameCode gameCode = gameCodeRepository.findById(code).orElse(null);

		if (gameCode != null && StringUtils.hasText(gameCode.getJsonPath())) {
			return gson.fromJson(gameCode.getJsonPath(), Permissions.class);
		}

		return null;
	}

	public GameModel getGame(long id) {
		Game game = gameRepository.findById(id).orElse(null);

		if (game != null && StringUtils.hasText(game.getGame())) {
			return gson.fromJson(game.getGame(), GameModel.class);
		}

		return null;
	}

	public Long createGame(GameModel gameModel) {
		Game game = new Game();
		game.setGame(gson.toJson(gameModel));
		game = gameRepository.save(game);
		return game.getId();
	}

	public void setGame(long id, GameModel gameModel) {
		Game game = gameRepository.findById(id).orElse(null);

		if (game != null) {
			game.setGame(gson.toJson(gameModel));
			gameRepository.save(game);
		}
	}

	public Settings getSettings(long gameId) {
		Setting setting = settingRepository.findById(gameId).orElse(null);

		if (setting != null && StringUtils.hasText(setting.getSettings())) {
			return gson.fromJson(setting.getSettings(), Settings.class);
		}

		return null;
	}

	public void createSettings(Settings settings, long gameId) {
		Setting setting = new Setting();
		setting.setSettings(gson.toJson(settings));
		setting.setGameId(gameId);
		settingRepository.save(setting);
	}

	public void setSettings(Settings settings, long gameId) {
		Setting setting = settingRepository.findById(gameId).orElse(null);

		if (settings != null && setting != null) {
			setting.setSettings(gson.toJson(settings));
			settingRepository.save(setting);
		}
	}

	/**
	 * Checks whether {@code gameUpdate} stays within {@code permissions} relative to
	 * {@code game}. Returns the first violated permission message, or {@code null} if
	 * the update is allowed. Shared between GameController and MessageHandler, whose
	 * game-update handling was previously duplicated.
	 */
	private boolean deepEquals(Object a, Object b) {
		return gson.toJson(a).equals(gson.toJson(b));
	}

	public String checkPermissions(GameModel game, GameModel gameUpdate, Permissions permissions) {
		if (permissions == null) {
			return null;
		}

		if (!permissions.isScenario() && !deepEquals(gameUpdate.getScenario(), game.getScenario())) {
			return "Permission(s) missing: scenario";
		}
		if (!permissions.isScenario() && !deepEquals(gameUpdate.getSections(), game.getSections())) {
			return "Permission(s) missing: scenario";
		}
		if (!permissions.isScenario()
				&& (gameUpdate.getEdition() != null && !gameUpdate.getEdition().equals(game.getEdition())
						|| game.getEdition() != null && !game.getEdition().equals(gameUpdate.getEdition()))) {
			return "Permission(s) missing: scenario";
		}
		if (!permissions.isElements() && !deepEquals(gameUpdate.getElementBoard(), game.getElementBoard())) {
			return "Permission(s) missing: elements";
		}
		if (!permissions.isLootDeck() && !deepEquals(gameUpdate.getLootDeck(), game.getLootDeck())) {
			return "Permission(s) missing: lootDeck";
		}
		if (!permissions.isRound() && gameUpdate.getRound() != game.getRound()) {
			return "Permission(s) missing: round";
		}
		if (!permissions.isRound() && !gameUpdate.getState().equals(game.getState())) {
			return "Permission(s) missing: round";
		}
		if (!permissions.isLevel() && gameUpdate.getLevel() != game.getLevel()) {
			return "Permission(s) missing: level";
		}
		if (!permissions.isAttackModifiers()
				&& !deepEquals(gameUpdate.getMonsterAttackModifierDeck(), game.getMonsterAttackModifierDeck())) {
			return "Permission(s) missing: attackModifiers";
		}
		if (!permissions.isAttackModifiers()
				&& !deepEquals(gameUpdate.getAllyAttackModifierDeck(), game.getAllyAttackModifierDeck())) {
			return "Permission(s) missing";
		}
		if (!permissions.isParty() && (!deepEquals(gameUpdate.getParty(), game.getParty())
				|| !deepEquals(gameUpdate.getParties(), game.getParties()))) {
			return "Permission(s) missing: party";
		}
		if (!permissions.isCharacters()) {
			for (GameCharacterModel updateCharacter : gameUpdate.getCharacters()) {
				boolean characterPermission = false;
				boolean roundPermissions = permissions.isRound() && gameUpdate.getState() != game.getState();
				boolean lootDeckPermissions = permissions.isLootDeck()
						&& !deepEquals(gameUpdate.getLootDeck(), game.getLootDeck());
				boolean scenarioPermissions = permissions.isScenario()
						&& !deepEquals(gameUpdate.getScenario(), game.getScenario());

				for (GameCharacterModel character : game.getCharacters()) {
					if (updateCharacter.getName().equals(character.getName())
							&& updateCharacter.getEdition().equals(character.getEdition())) {
						for (Identifier characterFigure : permissions.getCharacter()) {
							if (characterFigure.getName().equals(character.getName())
									&& characterFigure.getEdition().equals(character.getEdition())) {
								characterPermission = true;
								break;
							}
						}
						if (characterPermission) {
							break;
						} else {
							updateCharacter.getAttackModifierDeck()
									.setActive(character.getAttackModifierDeck().isActive());

							if (permissions.isRound()) {
								character.setOff(updateCharacter.isOff());
								character.setActive(updateCharacter.isActive());
								character.setEntityConditions(updateCharacter.getEntityConditions());
							}

							if (roundPermissions || scenarioPermissions) {
								character.setOff(updateCharacter.isOff());
								character.setActive(updateCharacter.isActive());
								character.setInitiative(updateCharacter.getInitiative());
								character.setEntityConditions(updateCharacter.getEntityConditions());
								character.setAttackModifierDeck(updateCharacter.getAttackModifierDeck());
							}

							if (scenarioPermissions) {
								character.setHealth(updateCharacter.getHealth());
								character.setMaxHealth(updateCharacter.getMaxHealth());
								character.setLoot(updateCharacter.getLoot());
								character.setLootCards(updateCharacter.getLootCards());
								character.setTreasures(updateCharacter.getTreasures());
								character.setExperience(updateCharacter.getExperience());
								character.setEntityConditions(updateCharacter.getEntityConditions());
								character.setSummons(updateCharacter.getSummons());
								character.setExhausted(updateCharacter.isExhausted());
								character.setToken(updateCharacter.getToken());
							}

							if (lootDeckPermissions) {
								character.setLoot(updateCharacter.getLoot());
								character.setLootCards(updateCharacter.getLootCards());
							}
							if (deepEquals(character, updateCharacter)) {
								characterPermission = true;
								break;
							}
						}
					}
				}
				if (!characterPermission) {
					return "Permission(s) missing: characters";
				}
			}
		}
		if (!permissions.isMonsters()) {
			for (GameMonsterModel updateMonster : gameUpdate.getMonsters()) {
				boolean monsterPermission = false;
				boolean roundPermissions = permissions.isRound() && gameUpdate.getState() != game.getState();
				boolean scenarioPermissions = permissions.isScenario()
						&& !deepEquals(gameUpdate.getScenario(), game.getScenario());
				for (GameMonsterModel monster : game.getMonsters()) {
					if (updateMonster.getName().equals(monster.getName())
							&& updateMonster.getEdition().equals(monster.getEdition())) {
						for (Identifier monsterFigure : permissions.getMonster()) {
							if (monsterFigure.getName().equals(monster.getName())
									&& monsterFigure.getEdition().equals(monster.getEdition())) {
								monsterPermission = true;
								break;
							}
						}
						if (monsterPermission) {
							break;
						} else {

							if (permissions.isRound()) {
								monster.setOff(updateMonster.isOff());
								monster.setActive(updateMonster.isActive());
								monster.setEntities(updateMonster.getEntities());
							}

							if (roundPermissions) {
								monster.setAbility(updateMonster.getAbility());
								monster.setAbilities(updateMonster.getAbilities());
								monster.setEntities(updateMonster.getEntities());
								monster.setActive(updateMonster.isActive());
								monster.setOff(updateMonster.isOff());
							}

							if (scenarioPermissions || deepEquals(updateMonster, monster)) {
								monsterPermission = true;
								break;
							}
						}
					}
				}
				if (!monsterPermission && !game.getMonsters().isEmpty() && !scenarioPermissions) {
					return "Permission(s) missing: monsters";
				}
			}
		}

		return null;
	}

}
