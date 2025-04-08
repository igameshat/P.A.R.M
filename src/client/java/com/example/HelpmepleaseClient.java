package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;

public class HelpmepleaseClient implements ClientModInitializer {
	private static final String API_URL = "https://earth.mikart.eu/tiles/players.json";

	@Override
	public void onInitializeClient() {
		// Register client-side command
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				registerMikFindCommand(dispatcher)
		);
	}

	private void registerMikFindCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("mikfind")
				.then(argument("playerName", StringArgumentType.string())
						.suggests(this::suggestServerPlayers)
						.executes(this::findPlayer)
				)
		);
	}

	private CompletableFuture<Suggestions> suggestServerPlayers(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
		// Get the current input for filtering
		String remaining = builder.getRemaining().toLowerCase();

		// Get server players list from the client
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.getNetworkHandler() != null && client.getNetworkHandler().getPlayerList() != null) {
			// Suggest only players that are actually on the server
			for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
				String playerName = entry.getProfile().getName();
				if (playerName.toLowerCase().startsWith(remaining)) {
					builder.suggest(playerName);
				}
			}
		}

		return builder.buildFuture();
	}

	private int findPlayer(CommandContext<FabricClientCommandSource> context) {
		String playerName = StringArgumentType.getString(context, "playerName");

		// Show searching message
		sendChatMessage(Text.literal("Searching for player: " + playerName + "..."));

		// Run the search asynchronously to prevent client freezing
		CompletableFuture.runAsync(() -> findPlayerDetails(playerName));

		return 1;
	}

	private void findPlayerDetails(String playerName) {
		try {
			// Fetch JSON from the URL
			HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);

			// Read the response
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
				StringBuilder response = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					response.append(line);
				}

				// Parse the JSON and find the player
				String jsonResponse = response.toString();
				Pattern playerPattern = Pattern.compile("\\{\"world\":\"([^\"]*)\",\"armor\":(\\d+),\"name\":\"" +
						Pattern.quote(playerName) + "\",\"x\":(-?\\d+),\"y\":(-?\\d+),\"health\":(\\d+),\"z\":(-?\\d+)");
				Matcher matcher = playerPattern.matcher(jsonResponse);

				// Send results to main thread
				MinecraftClient.getInstance().executeSync(() -> {
					if (matcher.find()) {
						String world = matcher.group(1);
						int armor = Integer.parseInt(matcher.group(2));
						int x = Integer.parseInt(matcher.group(3));
						int y = Integer.parseInt(matcher.group(4));
						int health = Integer.parseInt(matcher.group(5));
						int z = Integer.parseInt(matcher.group(6));

						// Format coordinates as clickable text
						String coordsText = String.format("X=%d, Y=%d, Z=%d", x, y, z);
						String clipboardCoords = String.format("%d %d %d", x, y, z);

						// Create clickable text component
						MutableText locationText = Text.literal("Location: ");
						MutableText coordsClickable = Text.literal(coordsText)
								.setStyle(Style.EMPTY
										.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, clipboardCoords))
										.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to copy coordinates to clipboard")))
										.withColor(net.minecraft.util.Formatting.YELLOW)
										.withUnderline(true));

						// Send player info to chat
						sendChatMessage(Text.literal("Player: " + playerName));
						sendChatMessage(Text.literal("World: " + world));
						sendChatMessage(Text.literal("Armor: " + armor));
						sendChatMessage(Text.literal("Health: " + health));
						sendChatMessage(locationText.append(coordsClickable));
					} else {
						sendChatMessage(Text.literal("Player " + playerName + " not found in the API."));
					}
				});
			}
		} catch (Exception e) {
			// Send error to main thread
			MinecraftClient.getInstance().executeSync(() ->
					sendChatMessage(Text.literal("Error finding player: " + e.getMessage()))
			);
		}
	}

	private void sendChatMessage(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(message, false);
		}
	}
}