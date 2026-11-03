package com.cheatprogram.core.setting;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.google.gson.JsonElement;

import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
	public final String id;
	public final T defaultValue;
	protected T value;
	/** Owning module, or null for client-wide settings. */
	public Module owner;
	private BooleanSupplier visibility = () -> true;

	protected Setting(String id, T defaultValue) {
		this.id = id;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
		Config.markDirty();
	}

	public void reset() {
		set(defaultValue);
	}

	/** Only show this setting while the condition holds (e.g. a mode-specific option). */
	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
		this.visibility = condition;
		return (S) this;
	}

	public boolean isVisible() {
		return visibility.getAsBoolean();
	}

	public String name() {
		return owner == null ? Lang.t("ui." + id) : Lang.setting(owner.id, id);
	}

	/** Short text shown in the module row, e.g. "40% Chance". Empty when nothing should be shown. */
	public abstract String summary();

	public abstract JsonElement save();

	/** Validate persisted data without changing the live setting or marking it dirty. */
	public void validateSerialized(JsonElement element) {
		if(element==null||element.isJsonNull())throw new IllegalArgumentException("Invalid null setting");
		if (defaultValue instanceof double[]) {
			if (!element.isJsonArray() || element.getAsJsonArray().size() != 2) throw new IllegalArgumentException("Invalid range");
			for (JsonElement number : element.getAsJsonArray()) if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !Double.isFinite(number.getAsDouble())) throw new IllegalArgumentException("Invalid number");
			return;
		}
		if (!element.isJsonPrimitive()) throw new IllegalArgumentException("Invalid setting type");
		var primitive=element.getAsJsonPrimitive();
		if (defaultValue instanceof Boolean && !primitive.isBoolean()) throw new IllegalArgumentException("Invalid boolean");
		if (defaultValue instanceof Number && (!primitive.isNumber() || !Double.isFinite(primitive.getAsDouble()))) throw new IllegalArgumentException("Invalid number");
		if (defaultValue instanceof String && !primitive.isString()) throw new IllegalArgumentException("Invalid string");
	}

	public abstract void load(JsonElement element);
}
