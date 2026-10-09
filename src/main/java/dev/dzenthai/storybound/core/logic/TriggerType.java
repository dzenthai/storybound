package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.MapCodec;

/** Registered in {@code storybound:trigger_type}; the registry id is the JSON {@code type}. */
public record TriggerType<T extends Trigger<E>, E>(MapCodec<T> codec) {}
