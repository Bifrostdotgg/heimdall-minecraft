/**
 * Test-only stand-ins for the few ChatControl classes Heimdall reaches by reflection.
 *
 * <p>ChatControl is paid and unpublished, so it cannot be a test dependency either. These classes
 * reproduce the exact names and signatures {@code ChatControlChannels} resolves, as taken from
 * ChatControl 12.2.18 with {@code javap}, so the reflective code runs for real against something
 * with the same shape. They live only in the test source set and never ship.
 */
package org.mineacademy.chatcontrol;
