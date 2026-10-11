/**
 * Test-only stand-ins for the PremiumVanish classes Heimdall reaches by reflection on Bukkit.
 *
 * <p>Same names, superclasses and signatures as PremiumVanishAPI (MIT,
 * github.com/LeonMangler/PremiumVanishAPI), so {@code BukkitVanish} runs its real reflective code
 * against something with the right shape. They live only in the test source set and never ship:
 * the real classes come with PremiumVanish itself.
 */
package de.myzelyam.api.vanish;
