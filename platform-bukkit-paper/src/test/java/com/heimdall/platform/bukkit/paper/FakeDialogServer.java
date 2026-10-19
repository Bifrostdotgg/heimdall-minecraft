package com.heimdall.platform.bukkit.paper;

import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryBuilderFactory;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.InlinedRegistryBuilderProvider;
import io.papermc.paper.registry.data.InstrumentRegistryEntry;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.DialogRegistryEntry;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.ItemDialogBody;
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.BooleanDialogInput;
import io.papermc.paper.registry.data.dialog.input.NumberRangeDialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.ConfirmationType;
import io.papermc.paper.registry.data.dialog.type.DialogListType;
import io.papermc.paper.registry.data.dialog.type.MultiActionType;
import io.papermc.paper.registry.data.dialog.type.NoticeType;
import io.papermc.paper.registry.data.dialog.type.ServerLinksType;
import io.papermc.paper.registry.set.RegistrySet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.MusicInstrument;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;

/**
 * The server half of Paper's dialog API, for tests: what a running Paper supplies through
 * {@link java.util.ServiceLoader} and this test JVM otherwise has none of.
 *
 * <p>Registered in {@code src/test/resources/META-INF/services}, so {@code DialogBase.builder},
 * {@code Dialog.create} and the rest run their real API code and land here. What the binding asked
 * for is then recorded in {@link #current}, and a test can check every argument it passed through
 * reflection. With {@link #current} null the providers throw, which is a server whose dialog
 * implementation is broken.
 */
public final class FakeDialogServer {

    /** What the binding built, or {@code null} for a server that cannot build anything. */
    static volatile Recorder current;

    private FakeDialogServer() {
    }

    static Recorder start() {
        Recorder recorder = new Recorder();
        current = recorder;
        return recorder;
    }

    private static Recorder recorder() {
        Recorder recorder = current;
        if (recorder == null) {
            throw new IllegalStateException("this test server cannot build dialogs");
        }
        return recorder;
    }

    /** Everything one dialog was built from. */
    static final class Recorder {

        final DialogBase.Builder base = mock(DialogBase.Builder.class, RETURNS_SELF);
        final DialogBase builtBase = mock(DialogBase.class);
        final ActionButton.Builder button = mock(ActionButton.Builder.class, RETURNS_SELF);
        final ActionButton builtButton = mock(ActionButton.class);
        final NoticeType notice = mock(NoticeType.class);
        final DialogRegistryEntry.Builder entry = mock(DialogRegistryEntry.Builder.class, RETURNS_SELF);
        final Dialog dialog = mock(Dialog.class);

        volatile Component title;
        volatile Component buttonLabel;
        volatile ClickEvent click;
        volatile ActionButton noticeButton;
        final List<Component> body = new CopyOnWriteArrayList<Component>();

        Recorder() {
            when(base.build()).thenReturn(builtBase);
            when(button.build()).thenReturn(builtButton);
        }
    }

    /** {@code DialogBase.builder}, {@code ActionButton.builder}, the bodies, actions and types. */
    public static final class Instances implements DialogInstancesProvider {

        @Override
        public DialogBase.Builder dialogBaseBuilder(Component title) {
            Recorder recorder = recorder();
            recorder.title = title;
            return recorder.base;
        }

        @Override
        public ActionButton.Builder actionButtonBuilder(Component label) {
            Recorder recorder = recorder();
            recorder.buttonLabel = label;
            return recorder.button;
        }

        @Override
        public DialogAction.StaticAction staticAction(ClickEvent click) {
            recorder().click = click;
            return mock(DialogAction.StaticAction.class);
        }

        @Override
        public PlainMessageDialogBody plainMessageDialogBody(Component contents) {
            recorder().body.add(contents);
            return mock(PlainMessageDialogBody.class);
        }

        @Override
        public PlainMessageDialogBody plainMessageDialogBody(Component contents, int width) {
            return plainMessageDialogBody(contents);
        }

        @Override
        public NoticeType notice() {
            return recorder().notice;
        }

        @Override
        public NoticeType notice(ActionButton button) {
            Recorder recorder = recorder();
            recorder.noticeButton = button;
            return recorder.notice;
        }

        @Override
        public DialogAction.CustomClickAction register(
                DialogActionCallback callback, ClickCallback.Options options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DialogAction.CommandTemplateAction commandTemplate(String template) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DialogAction.CustomClickAction customClick(Key id, BinaryTagHolder additions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ItemDialogBody.Builder itemDialogBodyBuilder(ItemStack item) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BooleanDialogInput.Builder booleanBuilder(String key, Component label) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NumberRangeDialogInput.Builder numberRangeBuilder(
                String key, Component label, float start, float end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SingleOptionDialogInput.Builder singleOptionBuilder(
                String key, Component label, List<SingleOptionDialogInput.OptionEntry> entries) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SingleOptionDialogInput.OptionEntry singleOptionEntry(
                String id, Component display, boolean initial) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TextDialogInput.Builder textBuilder(String key, Component label) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TextDialogInput.MultilineOptions multilineOptions(Integer maxLines, Integer height) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfirmationType confirmation(ActionButton yes, ActionButton no) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DialogListType.Builder dialogList(RegistrySet<Dialog> dialogs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultiActionType.Builder multiAction(List<ActionButton> actions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServerLinksType serverLinks(ActionButton exit, int columns, int buttonWidth) {
            throw new UnsupportedOperationException();
        }
    }

    /** {@code Dialog.create}: runs the binding's consumer against a recording entry builder. */
    public static final class Inlined implements InlinedRegistryBuilderProvider {

        @Override
        @SuppressWarnings("unchecked")
        public Dialog createDialog(Consumer<RegistryBuilderFactory<Dialog,
                ? extends DialogRegistryEntry.Builder>> value) {
            final Recorder recorder = recorder();
            RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder> factory =
                    mock(RegistryBuilderFactory.class);
            when(factory.empty()).thenReturn(recorder.entry);
            value.accept(factory);
            return recorder.dialog;
        }

        @Override
        public MusicInstrument createInstrument(Consumer<RegistryBuilderFactory<MusicInstrument,
                ? extends InstrumentRegistryEntry.Builder>> value) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Registries, for {@code Dialog}'s own static initialiser, which looks up the built-in dialogs.
     * They come back null here; nothing in the binding uses them.
     */
    public static final class Registries implements RegistryAccess {

        @Override
        @SuppressWarnings({"unchecked", "removal"})
        public <T extends org.bukkit.Keyed> Registry<T> getRegistry(Class<T> type) {
            return mock(Registry.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends org.bukkit.Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
            return mock(Registry.class);
        }
    }
}
