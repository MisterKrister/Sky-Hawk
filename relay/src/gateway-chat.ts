/** Wire-format counterpart of Sky-Myce discord-user-bot/src/global-chat.mjs. */
export const GLOBAL_ROOM = "global-chat";
export const snowflake = /^\d{17,20}$/;
export const minecraftId = /^[a-f0-9]{32}$/;
type Json = Record<string, any>;
const unsafeDisplay = /[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/gu;
const unsafeBody = /[\x00-\x08\x0b-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/gu;
const graphemes = new Intl.Segmenter("en", { granularity: "grapheme" });
export function bounded(value: string, maximum: number, suffix = ""): string {
  if (value.length <= maximum) return value;
  const limit = maximum - suffix.length; let end = 0;
  for (const part of graphemes.segment(value)) {
    if (part.index + part.segment.length > limit) break;
    end = part.index + part.segment.length;
  }
  for (const match of value.matchAll(/<a?:[A-Za-z0-9_]{1,32}:\d{17,20}>/gu))
    if (match.index < end && match.index + match[0].length > end) end = match.index;
  return value.slice(0, end) + suffix;
}
function display(user?: Json, member?: Json): string {
  return bounded(String(member?.nick ?? user?.global_name ?? user?.username ?? "Discord user").replace(unsafeDisplay, ""), 64) || "Discord user";
}
function list(value: unknown): Json[] { return Array.isArray(value) ? value.filter(item => item && typeof item === "object") : []; }
function link(value: unknown): string {
  if (typeof value !== "string" || value.length > 8192) return "";
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password ? url.href : "";
  } catch { return ""; }
}
const bodyUrl = /https?:\/\/[^\s<>"`]+/gu;
function textLink(value: string, offset: number, text: string): { url: string; suffix: string } {
  const wrapper = text[offset - 1];
  const suffix = wrapper === "(" && value.endsWith(")") ? ")" : wrapper === "[" && value.endsWith("]") ? "]" : "";
  return { url: link(suffix ? value.slice(0, -1) : value), suffix };
}
function attachmentUrl(url: URL): boolean {
  return url.protocol === "https:" && !url.port && ["cdn.discordapp.com", "media.discordapp.net"].includes(url.hostname) &&
    /^\/attachments\/\d{1,20}\/\d{1,20}\/[^/]+$/u.test(url.pathname);
}
/** Signing parameters identify an authorization, not another copy of the attachment. */
export function discordMediaKey(value: string): string {
  const valid = link(value);
  if (!valid) return "";
  const url = new URL(valid);
  if (attachmentUrl(url)) {
    url.hostname = "cdn.discordapp.com";
    for (const parameter of ["ex", "is", "hm"]) url.searchParams.delete(parameter);
  }
  return url.href;
}
function signedAttachment(value: string): boolean {
  const url = new URL(value);
  return attachmentUrl(url) && ["ex", "is", "hm"].every(parameter => !!url.searchParams.get(parameter));
}
export function discordBody(message: Json, maximum = 4096, previousLinks: readonly string[] = []): string {
  const mentions = new Map(list(message.mentions).slice(0, 100).map(user => [user.id, user]));
  const parts: { text: string; url?: string }[] = [];
  const add = (value: unknown, url?: string): void => {
    if (typeof value !== "string") return;
    const safe = value.replace(/<(@!?|@&|#)(\d{17,20})>/g, (_, kind, id) => {
      if (kind === "#") return "#channel";
      if (kind === "@&") return "@role";
      const user = mentions.get(id);
      return `@${display(user, user?.member)}`;
    })
      .replace(/\r\n?/g, "\n").replace(/\t/g, " ").replace(unsafeBody, "");
    if (safe.length) parts.push({ text: safe, url });
  };
  add(message.content);
  for (const embed of list(message.embeds).slice(0, 10)) {
    add(embed.author?.name); add(embed.title); add(embed.description);
    for (const field of list(embed.fields).slice(0, 25)) { add(field.name); add(field.value); }
    add(embed.footer?.text);
    for (const value of [embed.url, embed.author?.url, embed.image?.url, embed.thumbnail?.url]) {
      const url = link(value); if (url) add(url, url);
    }
  }
  for (const attachment of list(message.attachments).slice(0, 10)) {
    const url = link(attachment.url);
    if (url) add(`${attachment.filename ?? "Attachment"}: ${url}`, url);
  }
  // Choose from the actual URLs before rendering, so a signed URL can replace an earlier unsigned one.
  const preferred = new Map<string, string>();
  for (const part of parts) {
    const urls = part.url ? [part.url] : [...part.text.matchAll(bodyUrl)].map(match => textLink(match[0], match.index!, part.text).url).filter(Boolean);
    for (const url of urls) {
      const key = discordMediaKey(url), previous = preferred.get(key);
      if (!previous || signedAttachment(url) && !signedAttachment(previous)) preferred.set(key, url);
    }
  }
  const seen = new Set(previousLinks.map(discordMediaKey).filter(Boolean));
  const render = (url: string): string => {
    const key = discordMediaKey(url);
    if (seen.has(key)) return "";
    seen.add(key); return preferred.get(key) ?? url;
  };
  const rendered: string[] = [];
  for (const part of parts) {
    if (part.url) {
      const url = render(part.url);
      if (url) rendered.push(part.text.replace(part.url, url));
    } else {
      const text = part.text.replace(bodyUrl, (value, offset) => {
        const candidate = textLink(value, offset, part.text);
        return candidate.url ? render(candidate.url) + candidate.suffix : value;
      });
      if (text.trim()) rendered.push(text);
    }
  }
  return bounded(rendered.join("\n"), maximum, " … [truncated]");
}
/** Only identities of links actually present in the bounded delivery are retained for a delayed preview. */
export function discordMessageLinks(message: Json): string[] {
  const text = discordBody(message);
  return [...new Set([...text.matchAll(bodyUrl)].map(match => discordMediaKey(textLink(match[0], match.index!, text).url)).filter(Boolean))];
}
export function discordPacket(message: Json, guild: string, channel: string, ownBot: string, previousLinks: readonly string[] = []): Json | null {
  if (message.guild_id !== guild || message.channel_id !== channel || message.webhook_id ||
      !snowflake.test(ownBot) || message.author?.id === ownBot ||
      !snowflake.test(message.id ?? "") || !snowflake.test(message.author?.id ?? "") || !snowflake.test(channel)) return null;
  const text = discordBody(message, 4096, previousLinks);
  if (!text.trim()) return null;
  const packet: Json = { type: "discord_message", scope: "global", room: GLOBAL_ROOM, source: "discord", id: message.id,
    message_id: message.id, channelId: channel, discordId: message.author.id, from: display(message.author, message.member), text,
    model: 1, sourceId: message.id, sender: discordSender(message), media: discordMedia(message), revision: 0, reactions: [],
    mentions: [...new Set(list(message.mentions).map(user => user.id).filter(id => typeof id === "string" && snowflake.test(id)))].slice(0, 100) };
  const reference = message.message_reference, referenced = message.referenced_message;
  const referenceId = reference?.message_id ?? referenced?.id;
  if (snowflake.test(referenceId ?? "") && (reference?.channel_id ?? channel) === channel &&
      (!referenced || referenced.channel_id === channel && (referenced.guild_id === undefined || referenced.guild_id === guild))) {
    const ownCard = referenced?.author?.id === ownBot && !referenced?.webhook_id ? list(referenced.embeds)[0] : undefined;
    const cardName = typeof ownCard?.author?.name === "string" ? bounded(ownCard.author.name.replace(unsafeDisplay, ""), 64) : "";
    const cardText = typeof ownCard?.description === "string" ? bounded(ownCard.description.replace(/\\([\\`*_~|>])/gu, "$1").replace(unsafeBody, ""), 256) : "";
    packet.reply = { message_id: referenceId, from: referenced ? cardName || display(referenced.author, referenced.member) : "Unavailable message",
      text: referenced ? cardText || discordBody(referenced, 256) : "The original message is no longer available." };
  }
  return packet;
}
export function embedPacket(previous: Json, updated: Json, guild: string, channel: string, ownBot: string): Json | null {
  if (!previous || previous.id !== updated.id || previous.guild_id !== guild || previous.channel_id !== channel ||
      typeof previous.content !== "string" || previous.content !== updated.content || list(previous.embeds).length || !list(updated.embeds).length) return null;
  // A preview continuation carries only new embed content, not a second reply context.
  const packet = discordPacket({ id: updated.id, guild_id: updated.guild_id, channel_id: updated.channel_id,
    author: updated.author, webhook_id: updated.webhook_id, member: updated.member,
    content: "", embeds: updated.embeds, mentions: updated.mentions }, guild, channel, ownBot,
    Array.isArray(previous.links) ? previous.links : discordMessageLinks(previous));
  return packet ? { ...packet, part: "embeds", mentions: [] } : null;
}
export async function minecraftContent(packet: Json, scope = "global", room = GLOBAL_ROOM): Promise<Json | null> {
  if (packet.type !== "irc_message" || packet.scope !== scope || packet.room !== room || packet.source !== "minecraft" ||
      typeof packet.id !== "string" || !minecraftId.test(packet.id) || packet.message_id !== packet.id ||
      typeof packet.from !== "string" || !/^[A-Za-z0-9_]{1,16}$/.test(packet.from) ||
      typeof packet.text !== "string" || !packet.text.trim() || packet.text.length > (scope === "global" ? 1024 : 256) || unsafeBody.test(packet.text)) {
    unsafeBody.lastIndex = 0;
    return null;
  }
  unsafeBody.lastIndex = 0;
  const body = minecraftCard(packet, scope, room);
  if (!body) return null;
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(packet.id)));
  const nonce = [...digest].map(byte => byte.toString(16).padStart(2, "0")).join("").slice(0, 24);
  return { ...body, nonce, enforce_nonce: true };
}

export const REACTION_EMOJI = ['👍', '❤️', '😂', '🎉', '👀', '🔥'];
export function safeMediaUrl(value: unknown): string {
  if (typeof value !== 'string' || value.length > 2048) return '';
  try {
    const url = new URL(value), host = url.hostname.toLowerCase();
    if (url.protocol !== 'https:' || url.username || url.password || url.port && url.port !== '443' ||
        host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || !host.includes('.') ||
        host.includes(':') || /^\d+(?:\.\d+){3}$/u.test(host)) return '';
    if (attachmentUrl(url) && url.searchParams.has('ex')) {
      const expiry = url.searchParams.get('ex')!;
      if (!/^[a-f0-9]{1,16}$/iu.test(expiry) || BigInt(`0x${expiry}`) <= BigInt(Math.floor(Date.now() / 1000))) return '';
    }
    return url.href;
  } catch { return ''; }
}
function mediaType(value: unknown, url: string): string | undefined {
  const mime = typeof value === 'string' ? value.toLowerCase() : '', path = new URL(url).pathname;
  if (mime === 'image/gif' || /\.gif$/iu.test(path)) return 'gif';
  if (['video/mp4', 'video/webm'].includes(mime) || /\.(mp4|webm)$/iu.test(path)) return 'video';
  if (['image/png', 'image/jpeg', 'image/webp'].includes(mime) || /\.(png|jpe?g|webp)$/iu.test(path)) return 'image';
}
export function discordMedia(message: Json): Json[] {
  const chosen = new Map<string, Json>();
  const add = (value: unknown, raw: Json = {}) => {
    const url = safeMediaUrl(value); if (!url) return;
    const type = mediaType(raw.contentType ?? raw.content_type ?? raw.mime, url); if (!type) return;
    const key = discordMediaKey(url), previous = chosen.get(key);
    if (!previous && chosen.size >= 4) return;
    const preferredUrl = previous && (!signedAttachment(url) || signedAttachment(previous.url)) ? previous.url : url;
    const item: Json = { ...previous, id: snowflake.test(raw.id ?? '') ? raw.id : previous?.id ?? `media-${chosen.size}`, type, url: preferredUrl };
    const thumbnail = safeMediaUrl(raw.thumbnailUrl);
    if (thumbnail && type === 'video' && mediaType(undefined, thumbnail) !== 'video') item.thumbnailUrl = thumbnail;
    const name = raw.name ?? raw.filename;
    if (typeof name === 'string') item.name = bounded(name.replace(unsafeDisplay, ''), 80);
    const mime = raw.contentType ?? raw.content_type ?? raw.mime;
    if (typeof mime === 'string' && /^(image\/(png|jpeg|webp|gif)|video\/(mp4|webm))$/u.test(mime)) item.mime = mime;
    for (const [input, output, maximum] of [['size', 'bytes', 25_000_000], ['width', 'width', 16384], ['height', 'height', 16384]] as const)
      if (Number.isSafeInteger(raw[input]) && raw[input] >= 0 && raw[input] <= maximum) item[output] = raw[input];
    chosen.set(key, item);
  };
  for (const item of list(message.attachments).slice(0, 10)) add(item?.url, item ?? {});
  const content = typeof message.content === 'string' ? message.content : '';
  for (const match of content.matchAll(bodyUrl)) add(textLink(match[0], match.index, content).url);
  for (const item of list(message.embeds).slice(0, 10)) {
    const embed = item?.data ?? item;
    if (!embed || typeof embed !== 'object') continue;
    const gif = safeMediaUrl(embed.thumbnail?.url);
    if (embed.type === 'gifv' && gif && mediaType(undefined, gif) === 'gif') {
      // Discord supplies the animated GIF alongside its video variant; use that actual GIF once.
      add(gif, embed.thumbnail);
      continue;
    }
    add(embed.video?.url, { ...embed.video, thumbnailUrl: embed.thumbnail?.url });
    add(embed.image?.url, embed.image);
    if (chosen.get(discordMediaKey(embed.video?.url))?.type !== 'video') add(embed.thumbnail?.url, embed.thumbnail);
    add(embed.url);
  }
  return [...chosen.values()];
}
export function discordSender(message: Json): Json {
  const author = message.author ?? {}, sender: Json = { name: display(author, message.member), discordId: author.id };
  if (snowflake.test(author.id ?? '') && /^[a-f0-9]{32}$/u.test(author.avatar ?? ''))
    sender.avatarUrl = `https://cdn.discordapp.com/avatars/${author.id}/${author.avatar}.png?size=64`;
  return sender;
}

const rngRarities = new Set(["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "ULTIMATE", "SPECIAL", "VERY_SPECIAL", "ADMIN"]);
const rngItemNames: Record<string, string> = { NECRON_HANDLE: "Necron's Handle", GIANTS_SWORD: "Giant's Sword", DARK_CLAYMORE: "Dark Claymore",
  SHADOW_FURY: "Shadow Fury", IMPLOSION_SCROLL: "Implosion", WITHER_SHIELD_SCROLL: "Wither Shield", SHADOW_WARP_SCROLL: "Shadow Warp",
  WARDEN_HEART: "Warden Heart", JUDGEMENT_CORE: "Judgement Core", OVERFLUX_CAPACITOR: "Overflux Capacitor" };
const unsafeRngPresentation = /[\x00-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/u;
/** Match the community outbox's public projection; never add private identity fields to a Discord post. */
export async function rngContent(event: Json, now = Date.now()): Promise<Json | null> {
  if (!event || !minecraftId.test(event.id ?? "") || !/^[A-Z0-9_]{1,80}$/.test(event.item ?? "") ||
      !["dungeon", "slayer", "kuudra", "fishing"].includes(event.activity) || !Number.isSafeInteger(event.occurredAt) ||
      event.occurredAt > now + 60_000 || event.occurredAt < now - 900_000 || event.unverified !== true ||
      !(event.player === "Anonymous" || /^[A-Za-z0-9_]{1,16}$/.test(event.player ?? ""))) return null;
  if (event.rarity != null && !rngRarities.has(event.rarity) ||
      event.displayName != null && (typeof event.displayName !== "string" || event.displayName.length < 1 || event.displayName.length > 120 ||
        event.displayName !== event.displayName.trim() || unsafeRngPresentation.test(event.displayName)) ||
      event.floor != null && (event.activity !== "dungeon" || typeof event.floor !== "string" || !/^(?:E|[FM][1-7])$/.test(event.floor))) return null;
  const inert = (value: string): string => value.replace(/[\\`*_~|><\[\]()]/g, "\\$&").replace(/@/g, "@\u200b").replace(/:/g, ":\u200b");
  const display = event.displayName ?? rngItemNames[event.item] ?? event.item.toLowerCase().split("_").map((word: string) => word[0].toUpperCase() + word.slice(1)).join(" ");
  const fields = [{ name: "Activity", value: event.activity }];
  if (event.rarity != null) fields.push({ name: "Observed rarity", value: event.rarity.toLowerCase().replace(/_/g, " ") });
  if (event.floor != null) fields.push({ name: "Observed floor", value: event.floor });
  fields.push({ name: "Source", value: "Community submitted · unverified client acquisition report" }, { name: "Item variant", value: inert(event.item) });
  const nonce = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`rng:${event.id}`)))].map(byte => byte.toString(16).padStart(2, "0")).join("").slice(0, 24);
  return { embeds: [{ title: `Rare drop · ${inert(display)}`, color: 0x8b61eb,
    description: `**${event.player.replace(/_/g, "\\_")}** reported a supported rare drop.`, fields,
    timestamp: new Date(event.occurredAt).toISOString(), footer: { text: "Sky-Myce RNG · shared with consent; counts are not game drop probabilities" } }],
    allowed_mentions: { parse: [], replied_user: false }, nonce, enforce_nonce: true };
}
/** Only the authenticated backend may supply Minecraft identity and cosmetics metadata. */
export function minecraftCard(packet: Json, scope: string, room: string): Json | null {
  const maximum = scope === 'global' ? 1024 : 256;
  if (!(scope === 'global' && room === GLOBAL_ROOM || scope === 'room' && ['friends', 'testing'].includes(room)) || packet?.type !== 'irc_message' || packet.source !== 'minecraft' || packet.scope !== scope && !(scope === 'room' && packet.scope === undefined) ||
      packet.room !== room || !minecraftId.test(packet.id ?? '') || packet.message_id !== packet.id ||
      !/^[A-Za-z0-9_]{1,16}$/u.test(packet.from ?? '') || typeof packet.text !== 'string' ||
      !packet.text.trim() || packet.text.length > maximum || /[\x00-\x08\x0b-\x1f\x7f-\x9f\u00a7\u202a-\u202e\u2066-\u2069]/u.test(packet.text)) return null;
  if (packet.mentions !== undefined && (!Array.isArray(packet.mentions) || packet.mentions.length > 8 ||
      packet.mentions.some((id: unknown) => typeof id !== 'string' || !snowflake.test(id)))) return null;
  const users = scope === 'global' ? [...new Set<string>(packet.mentions ?? [])] : [];
  const escape = (value: string) => value.replace(/[\\`*_~|>]/g, '\\$&');
  const metadata = packet.sender?.name === packet.from ? packet.sender : {};
  const cosmetic = typeof metadata.cosmeticName === 'string' ? bounded(metadata.cosmeticName.replace(unsafeDisplay, ''), 64).trim() : '';
  const author: Json = { name: bounded(cosmetic && cosmetic !== packet.from ? `${cosmetic} (${packet.from})` : packet.from, 128) };
  const verifiedUuid = typeof metadata.uuid === 'string' ? metadata.uuid.replaceAll('-', '').toLowerCase() : '';
  if (minecraftId.test(verifiedUuid)) author.icon_url = `https://mc-heads.net/avatar/${verifiedUuid}/64`;
  const description = escape(packet.text), fields: Json[] = [];
  if (packet.reply && typeof packet.reply.from === "string" && typeof packet.reply.text === "string") {
    fields.push({ name: `Reply to ${bounded(packet.reply.from.replace(unsafeDisplay, ""), 64)}`,
      value: escape(bounded(packet.reply.text.replace(unsafeBody, ""), 256)) || "Original message unavailable" });
  }
  const embed: Json = { author, description: bounded(description, 4096), color: 0x3d9df5,
    footer: { text: scope === 'global' ? 'Minecraft · Sky-Myce Global' : 'Minecraft · Sky-Myce IRC' } };
  const links: string[] = [], seen = new Set<string>();
  for (const item of (Array.isArray(packet.media) ? packet.media : []).slice(0, 4)) {
    const url = safeMediaUrl(item?.url); if (!url || !['image', 'gif', 'video'].includes(item.type)) continue;
    const key = discordMediaKey(url); if (seen.has(key)) continue; seen.add(key);
    if ((item.type === 'image' || item.type === 'gif') && !embed.image) embed.image = { url };
    else if (item.type === 'video' && !embed.thumbnail && safeMediaUrl(item.thumbnailUrl)) embed.thumbnail = { url: safeMediaUrl(item.thumbnailUrl) };
    if (!packet.text.includes(url)) links.push(`[${item.type === 'video' ? 'Video' : 'Media'}](${url})`);
  }
  if (links.length) fields.push({ name: 'Media', value: bounded(links.join(' · '), 1024) });
  if (fields.length) embed.fields = fields;
  const body: Json = { embeds: [embed], allowed_mentions: users.length ? { parse: [], users, replied_user: false } : { parse: [], replied_user: false } };
  if (users.length) body.content = users.map(id => `<@${id}>`).join(' ');
  if (snowflake.test(packet.reply?.discordMessageId ?? '')) body.message_reference = { message_id: packet.reply.discordMessageId, fail_if_not_exists: false };
  return body;
}
export function bridgeOperationApplied(metadata: Json, packet: Json): boolean {
  if (metadata.deletedRevision !== undefined) return true;
  if (packet.operation === 'edit') return (metadata.bodyRevision ?? 0) >= packet.revision;
  if (packet.operation === 'reaction') return (metadata.reactionRevisions?.[packet.emoji] ?? 0) >= packet.revision;
  return false;
}
export function minecraftReactionFooter(packet: Json, scope: string): string {
  const counts = list(packet.reactions).filter(row => REACTION_EMOJI.includes(row.emoji) &&
    Number.isSafeInteger(row.count) && row.count > 0 && row.count <= 384).slice(0, 6);
  const summary = counts.map(row => row.emoji + ' ' + row.count).join('  ');
  return (scope === 'global' ? 'Minecraft · Sky-Myce Global' : 'Minecraft · Sky-Myce IRC') + (summary ? ' · ' + summary : '');
}
export function validBridgeOperation(packet: Json, scope: string, room: string, now = Date.now()): boolean {
  return (scope === 'global' && room === GLOBAL_ROOM || scope === 'room' && ['friends', 'testing'].includes(room)) && packet?.type === 'irc_operation' && packet.scope === scope && packet.room === room && minecraftId.test(packet.id ?? '') &&
    minecraftId.test(packet.messageId ?? '') && snowflake.test(packet.discordMessageId ?? '') &&
    Number.isSafeInteger(packet.revision) && packet.revision >= 1 && Number.isSafeInteger(packet.timestamp) &&
    packet.timestamp <= now + 30000 && packet.timestamp > now - 120000 && Number.isSafeInteger(packet.expiresAt) &&
    packet.expiresAt > now && packet.expiresAt <= packet.timestamp + 120000 && ['edit', 'delete', 'reaction'].includes(packet.operation) &&
    (packet.operation !== 'reaction' || REACTION_EMOJI.includes(packet.emoji) && typeof packet.active === 'boolean');
}
/** Event identity must use the Discord session/sequence, or an otherwise stable source revision. */
export function discordOperation(event: string, message: Json, previous: Json | undefined, id: string, guild: string, channel: string, ownBot: string): Json | null {
  if (message?.guild_id !== guild || message.channel_id !== channel || !snowflake.test(message.id ?? message.message_id ?? '') || !minecraftId.test(id)) return null;
  const reaction = event === 'MESSAGE_REACTION_ADD' || event === 'MESSAGE_REACTION_REMOVE';
  const actor = reaction ? message.user_id : previous?.author?.id ?? message.author?.id;
  if (!snowflake.test(actor ?? '') || reaction && (actor === ownBot || message.emoji?.id || !REACTION_EMOJI.includes(message.emoji?.name))) return null;
  const base: Json = { type: 'discord_operation', id, source: 'discord', scope: 'global', room: GLOBAL_ROOM,
    message_id: message.id ?? message.message_id, channelId: channel, discordId: actor };
  if (reaction) return { ...base, operation: 'reaction', emoji: message.emoji.name, active: event === 'MESSAGE_REACTION_ADD' };
  if (!previous || previous.webhook_id || message.author && message.author.id !== previous.author?.id) return null;
  if (event === 'MESSAGE_DELETE') return { ...base, operation: 'delete' };
  if (event !== 'MESSAGE_UPDATE') return null;
  const merged = { ...previous, ...message }, own = previous.author?.id === ownBot;
  const description = list(merged.embeds)[0]?.description;
  if (own && typeof description !== "string") return null;
  const text = own ? bounded(description.replace(/\\([\\`*_~|>])/gu, "$1").replace(unsafeBody, ""), 1024) : discordBody(merged);
  const media = discordMedia(merged);
  if (previous.author?.id === ownBot && previous.mapped !== true) return null;
  if (text === previous.text && JSON.stringify(media) === JSON.stringify(previous.media)) return null;
  const editedAt = typeof message.edited_timestamp === "string" ? Date.parse(message.edited_timestamp) : undefined;
  return { ...base, operation: 'edit', text, media, ...(Number.isSafeInteger(editedAt) ? { editedAt } : {}) };
}
