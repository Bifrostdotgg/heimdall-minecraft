package com.heimdall.platform.bukkit.itemimage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.heimdall.core.items.ChatItem;
import com.heimdall.core.items.CustomModelData;
import com.heimdall.core.items.HoverTags;
import com.heimdall.core.items.ItemTranslations;
import com.heimdall.core.testing.ItemCaptures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Model resolution against small fixture packs. */
class ModelResolverTest {

    @TempDir
    Path temp;

    /** The vanilla fragments every test leans on: the generated parents and a cube. */
    private Path vanilla() {
        return Fixtures.write(temp.resolve("vanilla"), Fixtures.files(
                "assets/minecraft/models/item/generated.json", "{\"parent\":\"builtin/generated\"}",
                "assets/minecraft/models/item/handheld.json", "{\"parent\":\"item/generated\"}",
                "assets/minecraft/models/item/heart_of_the_sea.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/heart_of_the_sea\"}}",
                "assets/minecraft/items/heart_of_the_sea.json",
                        "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/heart_of_the_sea\"}}",
                "assets/minecraft/models/block/cube.json",
                        "{\"elements\":[{\"from\":[0,0,0],\"to\":[16,16,16],\"faces\":{"
                        + "\"up\":{\"texture\":\"#up\"},\"north\":{\"texture\":\"#north\"},"
                        + "\"east\":{\"texture\":\"#east\"},\"south\":{\"texture\":\"#south\"},"
                        + "\"west\":{\"texture\":\"#west\"},\"down\":{\"texture\":\"#down\"}}}],"
                        + "\"display\":{\"gui\":{\"rotation\":[30,225,0],\"translation\":[0,0,0],"
                        + "\"scale\":[0.625,0.625,0.625]}}}",
                "assets/minecraft/models/block/cube_all.json",
                        "{\"parent\":\"block/cube\",\"textures\":{\"particle\":\"#all\",\"up\":\"#all\","
                        + "\"down\":\"#all\",\"north\":\"#all\",\"south\":\"#all\",\"east\":\"#all\","
                        + "\"west\":\"#all\"}}",
                "assets/minecraft/models/block/stone.json",
                        "{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"block/stone\"}}",
                "assets/minecraft/items/stone.json",
                        "{\"model\":{\"type\":\"model\",\"model\":\"block/stone\"}}"));
    }

    /** ItemsAdder's shape: it overrides the vanilla definition with a range dispatch. */
    private Path itemsAdder() {
        return Fixtures.write(temp.resolve("itemsadder"), Fixtures.files(
                "assets/minecraft/items/heart_of_the_sea.json",
                        "{\"model\":{\"type\":\"minecraft:range_dispatch\",\"property\":"
                        + "\"minecraft:custom_model_data\",\"index\":0,\"entries\":["
                        + "{\"threshold\":10001,\"model\":{\"type\":\"model\",\"model\":\"custom:item/other\"}},"
                        + "{\"threshold\":10002,\"model\":{\"type\":\"model\",\"model\":\"custom:item/warded_jar\"}},"
                        + "{\"threshold\":10003,\"model\":{\"type\":\"model\",\"model\":\"custom:item/third\"}}],"
                        + "\"fallback\":{\"type\":\"model\",\"model\":\"minecraft:item/heart_of_the_sea\"}}}",
                "assets/custom/models/item/warded_jar.json",
                        "{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"#jar\","
                        + "\"jar\":\"custom:item/warded_jar\"}}",
                "assets/custom/models/item/other.json",
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"custom:item/other\"}}",
                "assets/custom/models/item/third.json",
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"custom:item/third\"}}"));
    }

    private static ModelResolver.Icon resolve(PackStack stack, ChatItem item) {
        return new ModelResolver(stack).resolve(item, 0, Deadline.none());
    }

    private static ChatItem heart(float cmd) {
        return ChatItem.builder("heart_of_the_sea")
                .customModelData(new CustomModelData(Collections.singletonList(cmd),
                        null, null, null))
                .build();
    }

    @Test
    void theWardedJarResolvesThroughTheItemsAdderRangeDispatch() {
        PackStack stack = Fixtures.stack(itemsAdder(), vanilla());
        ChatItem jar = HoverTags.scan(ItemCaptures.wardedJar(), ItemTranslations.NONE).get(0).item();

        ModelResolver.Icon icon = resolve(stack, jar);

        assertEquals(1, icon.parts.size());
        assertTrue(icon.parts.get(0).flat());
        assertEquals(Arrays.asList("custom:item/warded_jar"), icon.parts.get(0).layers,
                "highest threshold at or below 10002, through a parent chain and a texture variable");
    }

    @Test
    void rangeDispatchPicksTheHighestThresholdAtOrBelowAndFallsBackBelowAll() {
        PackStack stack = Fixtures.stack(itemsAdder(), vanilla());
        assertEquals(Arrays.asList("custom:item/other"), resolve(stack, heart(10001.5f)).parts.get(0).layers);
        assertEquals(Arrays.asList("custom:item/third"), resolve(stack, heart(99999f)).parts.get(0).layers);
        assertEquals(Arrays.asList("item/heart_of_the_sea"),
                resolve(stack, heart(5f)).parts.get(0).layers, "below every threshold: fallback");
        assertEquals(Arrays.asList("item/heart_of_the_sea"),
                resolve(stack, ChatItem.builder("heart_of_the_sea").build()).parts.get(0).layers,
                "no custom model data at all: fallback");
    }

    @Test
    void withoutThePackTheVanillaDefinitionWins() {
        ModelResolver.Icon icon = resolve(Fixtures.stack(vanilla()), heart(10002f));
        assertEquals(Arrays.asList("item/heart_of_the_sea"), icon.parts.get(0).layers);
    }

    @Test
    void legacyOverridesMatchCustomModelDataAndTheLastMatchWins() {
        Path pack = Fixtures.write(temp.resolve("legacy"), Fixtures.files(
                "assets/minecraft/models/item/stick.json",
                        "{\"parent\":\"item/handheld\",\"textures\":{\"layer0\":\"item/stick\"},"
                        + "\"overrides\":[{\"predicate\":{\"custom_model_data\":1},\"model\":\"item/one\"},"
                        + "{\"predicate\":{\"custom_model_data\":5},\"model\":\"item/five\"}]}",
                "assets/minecraft/models/item/one.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/one\"}}",
                "assets/minecraft/models/item/five.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/five\"}}"));
        PackStack stack = Fixtures.stack(pack, vanilla());
        ChatItem.Builder stick = ChatItem.builder("stick");

        assertEquals(Arrays.asList("item/stick"), resolve(stack, stick.build()).parts.get(0).layers);
        assertEquals(Arrays.asList("item/one"), resolve(stack,
                stick.customModelData(CustomModelData.ofLegacy(3)).build()).parts.get(0).layers);
        assertEquals(Arrays.asList("item/five"), resolve(stack,
                stick.customModelData(CustomModelData.ofLegacy(7)).build()).parts.get(0).layers);
    }

    @Test
    void itemModelNamesADefinitionFirstAndAModelSecond() {
        Path pack = Fixtures.write(temp.resolve("models"), Fixtures.files(
                "assets/custom/items/ticket.json",
                        "{\"model\":{\"type\":\"model\",\"model\":\"custom:item/ticket\",\"tints\":"
                        + "[{\"type\":\"minecraft:constant\",\"value\":16711680}]}}",
                "assets/custom/models/item/ticket.json",
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"custom:item/ticket\"}}",
                "assets/custom/models/item/direct.json",
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"custom:item/direct\"}}"));
        PackStack stack = Fixtures.stack(pack, vanilla());

        ModelResolver.Part ticket = resolve(stack,
                ChatItem.builder("paper").itemModel("custom:ticket").build()).parts.get(0);
        assertEquals(Arrays.asList("custom:item/ticket"), ticket.layers);
        assertArrayEquals(new int[] {0xFF0000}, ticket.layerTints);

        // 1.21.2 / 1.21.3: item_model named a model directly.
        assertEquals(Arrays.asList("custom:item/direct"), resolve(stack,
                ChatItem.builder("paper").itemModel("custom:item/direct").build()).parts.get(0).layers);
    }

    @Test
    void selectConditionAndCompositeReadTheOtherCustomModelDataLists() {
        Path pack = Fixtures.write(temp.resolve("select"), Fixtures.files(
                "assets/minecraft/items/paper.json",
                        "{\"model\":{\"type\":\"composite\",\"models\":["
                        + "{\"type\":\"select\",\"property\":\"custom_model_data\",\"cases\":["
                        + "{\"when\":[\"gold\",\"amber\"],\"model\":{\"type\":\"model\",\"model\":\"item/gold\"}}],"
                        + "\"fallback\":{\"type\":\"model\",\"model\":\"item/plain\"}},"
                        + "{\"type\":\"condition\",\"property\":\"custom_model_data\",\"index\":1,"
                        + "\"on_true\":{\"type\":\"model\",\"model\":\"item/flagged\"},"
                        + "\"on_false\":{\"type\":\"empty\"}}]}}",
                "assets/minecraft/models/item/gold.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/gold\"}}",
                "assets/minecraft/models/item/plain.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/plain\"}}",
                "assets/minecraft/models/item/flagged.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/flagged\"}}"));
        PackStack stack = Fixtures.stack(pack, vanilla());

        ModelResolver.Icon both = resolve(stack, ChatItem.builder("paper").customModelData(
                new CustomModelData(null, Arrays.asList(false, true), Arrays.asList("amber"), null))
                .build());
        assertEquals(2, both.parts.size());
        assertEquals(Arrays.asList("item/gold"), both.parts.get(0).layers);
        assertEquals(Arrays.asList("item/flagged"), both.parts.get(1).layers);

        ModelResolver.Icon plain = resolve(stack, ChatItem.builder("paper").build());
        assertEquals(1, plain.parts.size());
        assertEquals(Arrays.asList("item/plain"), plain.parts.get(0).layers);
    }

    @Test
    void aBlockResolvesToElementsWithItsGuiTransform() {
        ModelResolver.Icon icon = resolve(Fixtures.stack(vanilla()), ChatItem.builder("stone").build());

        ModelResolver.Part part = icon.parts.get(0);
        assertFalse(part.flat());
        assertEquals(1, part.elements.size());
        assertEquals(6, part.elements.get(0).faces.size());
        assertEquals("block/stone", part.elements.get(0).faces.get(0).texture,
                "#up -> #all -> block/stone across three files");
        assertArrayEquals(new float[] {30, 225, 0}, part.gui.rotation, 0f);
        assertTrue(part.sideLit);
    }

    @Test
    void specialModelsUseTheBaseModelsParticle() {
        Path pack = Fixtures.write(temp.resolve("special"), Fixtures.files(
                "assets/minecraft/items/chest.json",
                        "{\"model\":{\"type\":\"minecraft:special\",\"base\":\"minecraft:item/chest\","
                        + "\"model\":{\"type\":\"minecraft:chest\",\"texture\":\"minecraft:normal\"}}}",
                "assets/minecraft/models/item/chest.json",
                        "{\"parent\":\"builtin/entity\",\"textures\":{\"particle\":\"block/oak_planks\"}}"));
        ModelResolver.Icon icon = resolve(Fixtures.stack(pack, vanilla()), ChatItem.builder("chest").build());
        assertEquals(Arrays.asList("block/oak_planks"), icon.parts.get(0).layers);
    }

    @Test
    void anUnknownItemIsAPlaceholder() {
        assertTrue(resolve(Fixtures.stack(vanilla()), ChatItem.builder("custom:mystery").build())
                .placeholder());
        assertTrue(resolve(Fixtures.stack(), ChatItem.builder("stone").build()).placeholder(),
                "no assets at all");
    }

    @Test
    void aParentLoopIsBoundedRatherThanFollowedForever() {
        Path pack = Fixtures.write(temp.resolve("loop"), Fixtures.files(
                "assets/minecraft/models/item/loop.json", "{\"parent\":\"item/loop\"}"));
        assertTrue(resolve(Fixtures.stack(pack), ChatItem.builder("loop").build()).placeholder());
    }

    @Test
    void overlaysForTheRunningFormatWinOverTheBasePack() throws Exception {
        Path pack = Fixtures.write(temp.resolve("overlaid"), Fixtures.files(
                "pack.mcmeta", "{\"pack\":{\"pack_format\":46},\"overlays\":{\"entries\":["
                        + "{\"formats\":[50,60],\"directory\":\"modern\"},"
                        + "{\"formats\":{\"min_inclusive\":1,\"max_inclusive\":10},\"directory\":\"ancient\"}]}}",
                "assets/minecraft/models/item/stick.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/base\"}}",
                "modern/assets/minecraft/models/item/stick.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/modern\"}}",
                "ancient/assets/minecraft/models/item/stick.json",
                        "{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/ancient\"}}"));
        Path base = vanilla();
        PackStack at55 = new PackStack(Arrays.asList(
                AssetRoot.WithOverlays.of(new AssetRoot.Directory(pack), 55),
                new AssetRoot.Directory(base)), "55");
        PackStack at46 = new PackStack(Arrays.asList(
                AssetRoot.WithOverlays.of(new AssetRoot.Directory(pack), 46),
                new AssetRoot.Directory(base)), "46");

        assertEquals(Arrays.asList("item/modern"),
                resolve(at55, ChatItem.builder("stick").build()).parts.get(0).layers);
        assertEquals(Arrays.asList("item/base"),
                resolve(at46, ChatItem.builder("stick").build()).parts.get(0).layers);
    }

    @Test
    void aZipPackIsReadThroughItsCentralDirectory() throws Exception {
        Path zip = temp.resolve("generated.zip");
        Files.write(zip, Fixtures.zip(Fixtures.files(
                "assets/minecraft/items/heart_of_the_sea.json",
                        "{\"model\":{\"type\":\"model\",\"model\":\"custom:item/zipped\"}}",
                "assets/custom/models/item/zipped.json",
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"custom:item/zipped\"}}")));
        AssetRoot.Zip root = new AssetRoot.Zip(zip);
        try {
            PackStack stack = new PackStack(Arrays.<AssetRoot>asList(root,
                    new AssetRoot.Directory(vanilla())), "zip");
            assertEquals(Arrays.asList("custom:item/zipped"),
                    resolve(stack, heart(1f)).parts.get(0).layers);
            assertEquals(null, root.read("../outside.json", 1024));
        } finally {
            root.close();
        }
    }
}
