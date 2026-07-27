/**
 * The one thing the whitelist and role-sync modules have to agree on.
 *
 * <p>A single interface, in core, because the two modules must not depend on each other: they are
 * independently toggleable from the dashboard, and a compile edge would mean disabling one broke the
 * build of the other. The directive itself already lives in core
 * ({@link com.heimdall.core.http.model.RoleSyncDirective}), so the verb belongs beside it.
 */
package com.heimdall.core.roles;
