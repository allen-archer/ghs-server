package de.champonthis.ghs.server.util;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import de.champonthis.ghs.server.businesslogic.json.CharacterProgressDeserializer;
import de.champonthis.ghs.server.businesslogic.json.CharacterProgressSerializer;
import de.champonthis.ghs.server.businesslogic.json.PartyDeserializer;
import de.champonthis.ghs.server.businesslogic.json.PartySerializer;
import de.champonthis.ghs.server.businesslogic.json.ScenarioStatsDeserializer;
import de.champonthis.ghs.server.businesslogic.json.ScenarioStatsSerializer;
import de.champonthis.ghs.server.model.CharacterProgress;
import de.champonthis.ghs.server.model.Party;
import de.champonthis.ghs.server.model.ScenarioStats;

@Configuration
public class GsonConfig {

	@Bean
	public Gson gson() {
		return new GsonBuilder().registerTypeAdapter(CharacterProgress.class, new CharacterProgressDeserializer())
				.registerTypeAdapter(CharacterProgress.class, new CharacterProgressSerializer())
				.registerTypeAdapter(ScenarioStats.class, new ScenarioStatsDeserializer())
				.registerTypeAdapter(ScenarioStats.class, new ScenarioStatsSerializer())
				.registerTypeAdapter(Party.class, new PartyDeserializer())
				.registerTypeAdapter(Party.class, new PartySerializer())
				.registerTypeAdapterFactory(new RequiredTypeAdapterFactory()).create();
	}
}
