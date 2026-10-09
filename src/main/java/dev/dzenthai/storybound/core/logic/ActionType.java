package dev.dzenthai.storybound.core.logic;

import com.mojang.serialization.MapCodec;

/** Registered in {@code storybound:action_type}; the registry id is the JSON {@code type}. */
public record ActionType<T extends Action>(MapCodec<T> codec) {}
