package com.cheatprogram.dev;
import com.cheatprogram.core.setting.Setting;
import com.google.gson.*;
public final class SettingSchemaCheck {
 static final class Probe<T> extends Setting<T>{Probe(T value){super("fixture",value);}public String summary(){return "";}public JsonElement save(){return JsonNull.INSTANCE;}public void load(JsonElement e){throw new AssertionError("Validation must not call load");}}
 static void reject(Setting<?> setting,String json){try{setting.validateSerialized(JsonParser.parseString(json));throw new AssertionError("Invalid profile value accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args){
  var bool=new Probe<>(true);bool.validateSerialized(JsonParser.parseString("false"));reject(bool,"1");reject(bool,"null");reject(bool,"{}");
  var number=new Probe<>(1.0);number.validateSerialized(JsonParser.parseString("4.5"));reject(number,"\"4\"");reject(number,"[]");
  var text=new Probe<>("");text.validateSerialized(JsonParser.parseString("\"saved platform option\""));reject(text,"false");
  var range=new Probe<>(new double[]{1,2});range.validateSerialized(JsonParser.parseString("[2,3]"));reject(range,"[2]");reject(range,"[2,\"3\"]");
  if(!bool.get()||number.get()!=1.0||!text.get().isEmpty())throw new AssertionError("Validation changed live settings");
  System.out.println("SETTING_SCHEMA_PASS typed profile validation is nonmutating and rejects malformed settings");
 }
}
