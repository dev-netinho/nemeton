package dev.nemeton.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Small reflection bridge to Floodgate/Cumulus forms without making Bedrock mandatory at compile time. */
public final class BedrockForms {
    private BedrockForms() {}

    public static boolean isBedrock(Player player) {
        if (player.getName().startsWith(".")) return true;
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean sendSimple(Plugin plugin, Player player, String title, String content, Consumer<Integer> onClick, String... buttons) {
        if (!isBedrock(player)) return false;
        try {
            Class<?> simpleForm = Class.forName("org.geysermc.cumulus.form.SimpleForm");
            Class<?> builderInterface = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder");
            Object builder = simpleForm.getMethod("builder").invoke(null);
            builderInterface.getMethod("title", String.class).invoke(builder, title);
            builderInterface.getMethod("content", String.class).invoke(builder, content);
            for (String button : buttons) builderInterface.getMethod("button", String.class).invoke(builder, button);
            Consumer<Object> handler = response -> Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    Object clicked = response.getClass().getMethod("clickedButtonId").invoke(response);
                    if (clicked instanceof Integer index) onClick.accept(index);
                } catch (ReflectiveOperationException exception) {
                    plugin.getLogger().log(Level.WARNING, "Falha ao ler resposta de formulário Bedrock", exception);
                }
            });
            builderInterface.getMethod("validResultHandler", Consumer.class).invoke(builder, handler);

            Object api = Class.forName("org.geysermc.floodgate.api.FloodgateApi").getMethod("getInstance").invoke(null);
            for (Method method : api.getClass().getMethods()) {
                if (!method.getName().equals("sendForm") || method.getParameterCount() != 2) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters[0] == UUID.class && parameters[1].isInstance(builder)) {
                    Object sent = method.invoke(api, player.getUniqueId(), builder);
                    return Boolean.TRUE.equals(sent);
                }
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().log(Level.FINE, "Formulários Bedrock indisponíveis; usando fallback em chat", exception);
        }
        return false;
    }
}
