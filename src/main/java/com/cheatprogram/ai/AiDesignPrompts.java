package com.cheatprogram.ai;

import com.cheatprogram.core.Lang;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Localized design prose with version-specific, locale-neutral data formats. */
public final class AiDesignPrompts {
    private static final JsonObject LANGUAGES=load();
    private static final String BUILDING_SAMPLE="""
            {"name":"","size":[3,4,5],"palette":[{"Name":"minecraft:air"},{"Name":"minecraft:stone_bricks","Properties":{}}],"fills":[{"from":[0,0,0],"to":[2,0,4],"block":1}]}""";
    private static final String PARTICLE_SAMPLE="""
            {"rounds":[{"name":"","duration":200,"hold":false,"emitters":[{"particle":"minecraft:end_rod","x":0,"y":1,"z":0,"every":3,"count":1,"path":{"kind":"circle","radius":1,"duration":100,"plane":"xz"}}]}]}""";
    private static final String EMITTER_FIELDS="""
            options: string; shown, rotateWithPlayer: boolean; symmetry: none / x / y / z / xy / xz / yz / xyz.
            x, y, z: −16 ≤ n ≤ 16; pitch, yaw, roll: −360 ≤ n ≤ 360; vx, vy, vz: −4 ≤ n ≤ 4.
            spread: 0 ≤ n ≤ 8; scale: 0.05 ≤ n ≤ 8; every: 1 ≤ n ≤ 1200; count: 1 ≤ n ≤ 100.
            start, duration: 0 ≤ n ≤ 72000; lifetime: 0 ≤ n ≤ 1200.""";
    private static final String PATH_FIELDS="""
            kind: none / line / circle / helix / figure8 / wave / spiral / bounce / custom; loop: loop / pingpong / once.
            plane: xz / xy / yz; interpolation: linear / smooth; clock: emitter / particle; nativeMotion: boolean.
            duration: 1 ≤ n ≤ 72000; radius: 0 ≤ n ≤ 16; height: −16 ≤ n ≤ 16; phase: 0 ≤ n ≤ 360.
            points: [{"x":0,"y":0,"z":0},{"x":1,"y":1,"z":1}].""";

    private AiDesignPrompts(){ }

    public static String building(String language,String version,BuildingDesignWorkshop.Options options){
        JsonObject words=words(language);
        String prompt=format(words,"buildingIntro",version)+"\n"+BUILDING_SAMPLE+"\n\n"+word(words,"buildingRules");
        if(options==null)return prompt;
        String quality=words.getAsJsonObject("quality").get(options.tier().name()).getAsString();
        return prompt+"\n\n"+format(words,"buildingWorkshop",options.tier().name(),quality,
                options.agents(),options.tier().reviewPasses());
    }

    public static String particles(String language,String version,String types){
        JsonObject words=words(language);
        return format(words,"particleIntro",version)+"\n"+PARTICLE_SAMPLE+"\n\n"
                +word(words,"particleRules")+"\n\n"+word(words,"emitterFields")+"\n"+EMITTER_FIELDS+"\n\n"
                +word(words,"pathFields")+"\n"+PATH_FIELDS+"\n\n"+word(words,"particleTypes")+"\n"+types;
    }

    static String exchange(String language,String kind,String id,int images,String instructions,String requirement,String previous,
                           JsonObject progress,String configuration){
        JsonObject words=words(language);
        StringBuilder prompt=new StringBuilder(format(words,"exchange",word(words,kind),id));
        prompt.append("\n\n").append(word(words,"setup")).append("\n```json\n")
                .append(configuration).append("\n```");
        if(images>0)prompt.append("\n\n").append(format(words,"images",images));
        if(progress!=null){
            // The MCP request contains potentially large geometry, so continuation can use a short clipboard prompt.
            return prompt.append("\n\n").append(word(words,"resume")).toString();
        }
        prompt.append("\n\n").append(word(words,"instructions")).append('\n').append(instructions);
        prompt.append("\n\n").append(word(words,"requirement")).append('\n').append(requirement);
        if(!previous.isBlank())prompt.append("\n\n").append(word(words,"previous")).append('\n').append(previous);
        return prompt.toString();
    }

    static String supportedLanguage(String language){return language!=null&&LANGUAGES.has(language)?language:"en";}
    static String gameplayInstructions(String kind) {
        return gameplayInstructions(kind, null);
    }

    static String gameplayInstructions(String kind, JsonObject character) {
        String prefix = "ui.ai.mcp.";
        boolean player = "player".equals(kind);
        StringBuilder prompt = new StringBuilder(Lang.t(prefix + kind));
        prompt.append("\n\n").append(Lang.t(prefix + (player ? "playerWorkflow" : "workflow")));
        if (player && character != null) appendCharacter(prompt, character);
        return prompt.append("\n\n").append(Lang.t(prefix + "setup")).toString();
    }

    private static void appendCharacter(StringBuilder prompt, JsonObject character) {
        prompt.append("\n\n").append(Lang.t("ui.ai.mcp.character"))
                .append("\n").append(Lang.t("ui.ai.mcp.characterRules"));
        appendCharacterField(prompt, "ui.aip.name", characterText(character, "name"));
        appendCharacterField(prompt, "ui.aip.persona", characterText(character, "persona"));
        appendCharacterField(prompt, "ui.aip.personality", characterText(character, "personality"));
        String gender = characterText(character, "gender");
        appendCharacterField(prompt, "ui.aip.gender", translatedChoice("mode.ai_gender_", gender));
        if ("custom".equals(gender)) {
            appendCharacterField(prompt, "ui.aip.customGender", characterText(character, "customGender"));
        }
        appendCharacterField(prompt, "ui.aip.mode", translatedChoice("mode.ai_", characterText(character, "mode")));
        appendCharacterField(prompt, "ui.aip.language", translatedChoice("mode.ai_language_", characterText(character, "language")));
        boolean freePlaying = character.has("freePlaying") && character.get("freePlaying").getAsBoolean();
        appendCharacterField(prompt, "ui.aip.play", Lang.t(freePlaying ? "ui.on" : "ui.off"));
    }

    private static String translatedChoice(String prefix, String value) {
        String key = prefix + value;
        return Lang.has(key) ? Lang.t(key) : Lang.t("ui.ai.mcp.unset");
    }

    private static void appendCharacterField(StringBuilder prompt, String label, String value) {
        prompt.append("\n\n").append(Lang.t(label)).append("\n")
                .append(value.isBlank() ? Lang.t("ui.ai.mcp.unset") : value);
    }

    private static String characterText(JsonObject character, String key) {
        var value = character.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }
    public static String resume(String language){return word(words(language),"resume");}
    private static JsonObject words(String language){return LANGUAGES.getAsJsonObject(supportedLanguage(language));}
    private static String word(JsonObject words,String key){return words.get(key).getAsString();}
    private static String format(JsonObject words,String key,Object... args){
        return String.format(java.util.Locale.ROOT,word(words,key),args);
    }
    private static JsonObject load(){
        var resource=Objects.requireNonNull(AiDesignPrompts.class.getResourceAsStream("/assets/cheatprogram/ai/design_prompts.json"),"design_prompts.json");
        try(var reader=new InputStreamReader(resource,StandardCharsets.UTF_8)){
            return JsonParser.parseReader(reader).getAsJsonObject();
        }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
}
