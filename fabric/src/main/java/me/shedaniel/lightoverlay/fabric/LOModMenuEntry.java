package me.shedaniel.lightoverlay.fabric;

import io.github.prospector.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.util.math.MathHelper;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

public class LOModMenuEntry implements ModMenuApi {
    @Override
    public String getModId() {
        return "lightoverlay";
    }
    
    @Override
    public Function<Screen, ? extends Screen> getConfigScreenFactory() {
        return this::getConfigScreenByCloth;
    }
    
    public Screen getConfigScreenByCloth(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent).setTitle("key.lightoverlay.category");
        
        ConfigEntryBuilder eb = builder.getEntryBuilder();
        ConfigCategory general = builder.getOrCreateCategory("config.lightoverlay.general");
        general.addEntry(eb.startTextDescription("§7" + I18n.translate("description.lightoverlay.caching")).build());
        general.addEntry(eb.startBooleanToggle("config.lightoverlay.caching", LightOverlay.caching).setDefaultValue(false).setSaveConsumer(bool -> LightOverlay.caching = bool).build());
        general.addEntry(eb.startIntSlider("config.lightoverlay.reach", LightOverlay.reach, 1, 64).setDefaultValue(12).setTextGetter(integer -> "Reach: " + integer + " Blocks").setSaveConsumer(integer -> LightOverlay.reach = integer).build());
        IntegerSliderEntry crossLevel = eb.startIntSlider("config.lightoverlay.crossLevel", LightOverlay.crossLevel, 0, 15).setDefaultValue(7).setTextGetter(integer -> "Cross Level: " + integer).setSaveConsumer(integer -> LightOverlay.crossLevel = integer).build();
        general.addEntry(crossLevel);
        general.addEntry(eb.startIntSlider("config.lightoverlay.secondaryLevel", LightOverlay.secondaryLevel, -1, 15)
                .setErrorSupplier(integer -> {
                    if (integer >= 0 && integer >= crossLevel.getValue()) return Optional.of("Secondary Level cannot be higher than Cross Level!");
                    return Optional.empty();
                }).setDefaultValue(-1).setTextGetter(integer -> integer < 0 ? "Off" : "Level: " + integer).setSaveConsumer(integer -> LightOverlay.secondaryLevel = integer).build());
        general.addEntry(eb.startBooleanToggle("config.lightoverlay.showNumber", LightOverlay.showNumber).setDefaultValue(false).setSaveConsumer(bool -> LightOverlay.showNumber = bool).build());
        general.addEntry(eb.startBooleanToggle("config.lightoverlay.smoothLines", LightOverlay.smoothLines).setDefaultValue(true).setSaveConsumer(bool -> LightOverlay.smoothLines = bool).build());
        general.addEntry(eb.startBooleanToggle("config.lightoverlay.underwater", LightOverlay.underwater).setDefaultValue(false).setSaveConsumer(bool -> LightOverlay.underwater = bool).build());
        general.addEntry(eb.startIntSlider("config.lightoverlay.lineWidth", MathHelper.floor(LightOverlay.lineWidth * 100), 100, 700).setDefaultValue(100).setTextGetter(integer -> "Light Width: " + LightOverlay.FORMAT.format(integer / 100d)).setSaveConsumer(integer -> LightOverlay.lineWidth = integer / 100f).build());
        general.addEntry(eb.startStrField("config.lightoverlay.yellowColor", "#" + toStringColor(LightOverlay.yellowColor))
                .setDefaultValue("#FFFF00")
                .setSaveConsumer(str -> LightOverlay.yellowColor = toIntColor(str))
                .setErrorSupplier(s -> {
                    if (!s.startsWith("#") || s.length() != 7 || !isInt(s.substring(1)))
                        return Optional.of(I18n.translate("config.lightoverlay.invalidColor"));
                    else return Optional.empty();
                })
                .build()
        );
        general.addEntry(eb.startStrField("config.lightoverlay.redColor", "#" + toStringColor(LightOverlay.redColor))
                .setDefaultValue("#FF0000")
                .setSaveConsumer(str -> LightOverlay.redColor = toIntColor(str))
                .setErrorSupplier(s -> {
                    if (!s.startsWith("#") || s.length() != 7 || !isInt(s.substring(1)))
                        return Optional.of(I18n.translate("config.lightoverlay.invalidColor"));
                    else return Optional.empty();
                })
                .build()
        );
        general.addEntry(eb.startStrField("config.lightoverlay.secondaryColor", "#" + toStringColor(LightOverlay.secondaryColor))
                .setDefaultValue("#0000FF")
                .setSaveConsumer(str -> LightOverlay.secondaryColor = toIntColor(str))
                .setErrorSupplier(s -> {
                    if (!s.startsWith("#") || s.length() != 7 || !isInt(s.substring(1)))
                        return Optional.of(I18n.translate("config.lightoverlay.invalidColor"));
                    else return Optional.empty();
                })
                .build()
        );
        
        return builder.setSavingRunnable(() -> {
            try {
                LightOverlay.saveConfig(LightOverlay.configFile);
            } catch (Exception e) {
                e.printStackTrace();
            }
            LightOverlay.loadConfig(LightOverlay.configFile);
        }).build();
    }
    
    private boolean isInt(String s) {
        try {
            Integer.parseInt(s, 16);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    private int toIntColor(String str) {
        String substring = str.substring(1);
        int r = Integer.parseInt(substring.substring(0, 2), 16);
        int g = Integer.parseInt(substring.substring(2, 4), 16);
        int b = Integer.parseInt(substring.substring(4, 6), 16);
        return (r << 16) + (g << 8) + b;
    }
    
    private String toStringColor(int toolColor) {
        String r = Integer.toHexString((toolColor >> 16) & 0xFF);
        String g = Integer.toHexString((toolColor >> 8) & 0xFF);
        String b = Integer.toHexString((toolColor >> 0) & 0xFF);
        if (r.length() == 1)
            r = "0" + r;
        if (g.length() == 1)
            g = "0" + g;
        if (b.length() == 1)
            b = "0" + b;
        return (r + g + b).toUpperCase(Locale.ROOT);
    }
}