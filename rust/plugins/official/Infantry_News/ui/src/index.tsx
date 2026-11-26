// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Infantry_News' web UI half (PLUGIN-AGENTS-1): it draws the card:news chat card through the `chat.card` slot, in every
// chat (desktop, browser, phone). module.json `ui.contributes` declares `{"slot": "chat.card", "id": "infantry.news.card",
// "card": "news"}`. Without this plugin a card:news block draws as the app's generic card.
import { definePlugin, UI_API_VERSION, type ChatCardProps } from "@infantry/ui-plugin-api";
import { NewsCard } from "./news-card";
import "./index.css";

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    ctx.register<ChatCardProps>("infantry.news.card", { render: ({ card }) => <NewsCard link={ctx.link} body={card.body} /> });
  },
});
