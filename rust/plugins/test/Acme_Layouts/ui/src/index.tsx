// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Acme_Layouts' web UI half (test plugin, PLUGIN-AGENTS-1): draws card:acme-weather through the `chat.card` slot.
import { definePlugin, UI_API_VERSION, type ChatCardProps } from "@infantry/ui-plugin-api";
import { WeatherCard } from "./weather-card";
import "./index.css";

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    ctx.register<ChatCardProps>("acme.layouts.weather", { render: ({ card }) => <WeatherCard body={card.body} /> });
  },
});
