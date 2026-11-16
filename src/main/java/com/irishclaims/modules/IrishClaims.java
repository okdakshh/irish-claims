package com.irishclaims.modules;

import com.irishclaims.IrishClaimsAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces server messages (titles, subtitles, action bar and chat) with Irish versions.
 * Colours and formatting from the server are kept: each coloured piece of the message
 * is translated separately, so the player name stays green, etc.
 */
public class IrishClaims extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> titles = sgGeneral.add(new BoolSetting.Builder()
        .name("titles")
        .description("Translate the big text in the middle of the screen (titles and subtitles).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> actionBar = sgGeneral.add(new BoolSetting.Builder()
        .name("action-bar")
        .description("Translate the small text above the hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat")
        .description("Translate matching server messages in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> rules = sgGeneral.add(new StringListSetting.Builder()
        .name("translations")
        .description("One per line: English text => Irish text. Not case-sensitive; ' and ’ both match.")
        .defaultValue(
            "You've entered your claim => Tá tú tar éis dul isteach i do chríoch féin",
            "You've left your claim => D'fhág tú do chríoch féin",
            "You've entered the friendly territory of => Tá tú tar éis dul isteach i gcríoch chairdiúil",
            "You've left the friendly territory of => D'fhág tú críoch chairdiúil",
            "You've entered the hostile territory of => Tá tú tar éis dul isteach i gcríoch naimhdeach",
            "You've left the hostile territory of => D'fhág tú críoch naimhdeach",
            "You've entered the enemy territory of => Tá tú tar éis dul isteach i gcríoch namhad",
            "You've left the enemy territory of => D'fhág tú críoch namhad",
            "You've entered the neutral territory of => Tá tú tar éis dul isteach i gcríoch neodrach",
            "You've left the neutral territory of => D'fhág tú críoch neodrach"
        )
        .build()
    );

    private record Rule(Pattern pattern, String replacement) {}

    private List<Rule> compiled = List.of();
    private List<String> compiledFrom = null;

    public IrishClaims() {
        super(IrishClaimsAddon.CATEGORY, "irish-claims", "Shows server claim messages in Irish (Gaeilge).");
    }

    // ---------------------------------------------------------------- packets

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        Packet<?> packet = event.packet;
        Packet<ClientGamePacketListener> replacement = null;

        if (packet instanceof ClientboundSetTitleTextPacket p && titles.get()) {
            Component t = translate(p.text());
            if (t != null) replacement = new ClientboundSetTitleTextPacket(t);
        } else if (packet instanceof ClientboundSetSubtitleTextPacket p && titles.get()) {
            Component t = translate(p.text());
            if (t != null) replacement = new ClientboundSetSubtitleTextPacket(t);
        } else if (packet instanceof ClientboundSetActionBarTextPacket p && actionBar.get()) {
            Component t = translate(p.text());
            if (t != null) replacement = new ClientboundSetActionBarTextPacket(t);
        } else if (packet instanceof ClientboundSystemChatPacket p && (p.overlay() ? actionBar.get() : chat.get())) {
            Component t = translate(p.content());
            if (t != null) replacement = new ClientboundSystemChatPacket(t, p.overlay());
        }

        if (replacement == null) return;

        // Drop the English packet and hand Minecraft the Irish one instead (on the game thread).
        event.setCancelled(true);
        final Packet<ClientGamePacketListener> irish = replacement;
        mc.execute(() -> {
            ClientPacketListener listener = mc.getConnection();
            if (listener != null) irish.handle(listener);
        });
    }

    // ------------------------------------------------------------ translating

    /** Returns the translated text, or null if nothing matched. */
    private Component translate(Component original) {
        if (original == null) return null;
        List<Rule> active = rules();
        if (active.isEmpty()) return null;

        // Quick check on the whole plain string first so normal messages cost almost nothing.
        String plain = original.getString();
        boolean any = false;
        for (Rule r : active) {
            if (r.pattern().matcher(plain).find()) { any = true; break; }
        }
        if (!any) return null;

        MutableComponent out = Component.empty();
        boolean[] changed = {false};

        original.visit((style, text) -> {
            String result = text;
            for (Rule r : active) {
                Matcher m = r.pattern().matcher(result);
                if (m.find()) {
                    result = m.replaceAll(Matcher.quoteReplacement(r.replacement()));
                    changed[0] = true;
                }
            }
            out.append(Component.literal(result).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);

        return changed[0] ? out : null;
    }

    private synchronized List<Rule> rules() {
        List<String> current = rules.get();
        if (current.equals(compiledFrom)) return compiled;

        List<Rule> list = new ArrayList<>();
        for (String line : current) {
            int arrow = line.indexOf("=>");
            if (arrow < 0) continue;
            String english = line.substring(0, arrow).trim();
            String irish = line.substring(arrow + 2).trim();
            if (english.isEmpty()) continue;
            list.add(new Rule(toPattern(english), irish));
        }

        compiled = List.copyOf(list);
        compiledFrom = List.copyOf(current);
        return compiled;
    }

    /** Literal text match, ignoring case, where an apostrophe matches ' or ’ (or no apostrophe at all). */
    private static Pattern toPattern(String english) {
        String[] parts = english.split("['’]", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) regex.append("['’]?");
            regex.append(Pattern.quote(parts[i]));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
