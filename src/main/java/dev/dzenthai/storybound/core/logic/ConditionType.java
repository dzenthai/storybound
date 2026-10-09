package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.MapCodec;

/** Registered in {@code storybound:condition_type}; the registry id is the JSON {@code type}. */
public record ConditionType<T extends Condition>(MapCodec<T> codec) {}
