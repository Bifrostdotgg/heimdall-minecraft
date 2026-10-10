package com.heimdall.platform.common;

import com.heimdall.core.update.HotSwap;
import com.heimdall.shell.contract.ShellContext;
import com.heimdall.shell.contract.StagedCore;
import java.nio.file.Path;

/**
 * {@link HotSwap} over the shell this core generation runs under (departure D87).
 *
 * <p>Thin on purpose: the shell does the extracting, the checking against the recorded hash and the
 * contract comparison, because it is the side that has to trust the result. This only translates
 * between the core's updater and the shell's contract.
 */
public final class ShellHotSwap implements HotSwap {

    private final ShellContext shell;

    public ShellHotSwap(ShellContext shell) {
        this.shell = shell;
    }

    @Override
    public Staged stage(Path releaseJar, String expectedSha256) {
        final StagedCore staged = shell.stageRelease(releaseJar, expectedSha256);
        if (!staged.swappable()) {
            return Staged.refused(staged.problem());
        }
        return new Staged() {
            @Override
            public boolean swappable() {
                return true;
            }

            @Override
            public String problem() {
                return "";
            }

            @Override
            public String version() {
                return staged.identity().version();
            }

            @Override
            public boolean swap(Object audience) {
                return shell.swapTo(staged, audience);
            }
        };
    }
}
