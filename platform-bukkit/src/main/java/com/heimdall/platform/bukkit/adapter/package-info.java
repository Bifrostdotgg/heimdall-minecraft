/**
 * The parts of the Bukkit binding that differ across thirteen years of server API.
 *
 * <p>One jar covers Spigot 1.8.8 through Paper 1.21, and the rule every class here follows is
 * <strong>probe for the capability, not for the brand</strong>. "Is this Paper?" is wrong in both
 * directions — Paper 1.12.2 says yes and lacks the API, Purpur says no and has it — and both
 * mistakes fail at runtime on somebody else's server.
 */
package com.heimdall.platform.bukkit.adapter;
