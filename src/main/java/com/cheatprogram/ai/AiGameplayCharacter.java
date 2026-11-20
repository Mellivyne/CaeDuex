package com.cheatprogram.ai;

import com.google.gson.JsonObject;

/** A copy of the selected character fields used by the external gameplay session. */
final class AiGameplayCharacter {
    private AiGameplayCharacter() {}

    static JsonObject capture(AiPlayers.Profile profile) {
        String[] credentials = {profile.apiKey(), AiPlayers.sharedConfig().apiKey()};
        JsonObject character = new JsonObject();
        character.addProperty("name", text(profile.name(), credentials));
        character.addProperty("persona", text(profile.persona(), credentials));
        character.addProperty("personality", text(profile.personality(), credentials));
        character.addProperty("gender", text(profile.gender(), credentials));
        character.addProperty("customGender", text(profile.customGender(), credentials));
        character.addProperty("mode", text(profile.mode(), credentials));
        character.addProperty("language", text(profile.language(), credentials));
        character.addProperty("freePlaying", profile.freePlaying());
        return character;
    }

    static String safeMessage(AiPlayers.Profile profile, String message) {
        return text(message, new String[]{profile.apiKey(), AiPlayers.sharedConfig().apiKey()});
    }

    private static String text(String value, String[] credentials) {
        String result = value == null ? "" : value.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", "");
        for (String credential : credentials) {
            if (credential != null && !credential.isEmpty()) result = result.replace(credential, "[hidden]");
        }
        return result;
    }
}
