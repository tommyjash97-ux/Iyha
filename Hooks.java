package net.iyha.core;

/** Optional integrations, loaded by reflection so the plugin works without them. */
interface Hook {
    void enable();

    void disable();
}
