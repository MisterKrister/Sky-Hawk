import minecraftKeys from "./minecraft-keys.json";
// Public trust roots from https://api.minecraftservices.com/publickeys (2026-09-22).
// ponytail: bundled roots avoid blocked Cloudflare egress; refresh/redeploy when Mojang rotates keys.

const encoder = new TextEncoder();
function decode(value: unknown, max: number): Uint8Array<ArrayBuffer> {
  if (typeof value !== "string" || value.length === 0 || value.length > max || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) throw new Error();
  return Uint8Array.from(atob(value), c => c.charCodeAt(0));
}

async function signedBy(keys: { publicKey: string }[], signature: Uint8Array, data: Uint8Array): Promise<boolean> {
  for (const entry of keys) {
    const key = await crypto.subtle.importKey("spki", decode(entry.publicKey, 4096),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"]);
    if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, data)) return true;
  }
  return false;
}

/** Verify Mojang's UUID-bound certificate, ownership of its key, and signed current profile name. */
export async function verifyAccount(data: Record<string, unknown>, challenge: string): Promise<{ name: string; id: string } | null> {
  try {
    if (typeof data.name !== "string" || !/^[A-Za-z0-9_]{1,16}$/.test(data.name) ||
        typeof data.uuid !== "string" || !/^[a-f0-9]{32}$/.test(data.uuid) ||
        typeof data.expires !== "number" || !Number.isSafeInteger(data.expires) || data.expires <= Date.now()) return null;
    const publicKey = decode(data.publicKey, 1024);
    // Matches Minecraft ProfilePublicKey.Data.signedPayload: UUID + expiry (big endian) + DER key.
    const certificate = new Uint8Array(24 + publicKey.length);
    certificate.set(Uint8Array.from(data.uuid.match(/../g)!, byte => parseInt(byte, 16)));
    new DataView(certificate.buffer).setBigInt64(16, BigInt(data.expires));
    certificate.set(publicKey, 24);
    if (!await signedBy(minecraftKeys.playerCertificateKeys, decode(data.keySignature, 1024), certificate)) return null;

    const key = await crypto.subtle.importKey("spki", publicKey, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    const proof = encoder.encode(`SkyMyce relay v2\n${challenge}\n${data.uuid}\n${data.name}`);
    if (!await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, decode(data.proof, 1024), proof)) return null;

    const profileBytes = decode(data.profile, 4096);
    if (!await signedBy(minecraftKeys.profilePropertyKeys, decode(data.profileSignature, 1024), encoder.encode(data.profile as string))) return null;
    const profile = JSON.parse(new TextDecoder().decode(profileBytes));
    if (profile.profileId !== data.uuid || typeof profile.profileName !== "string" ||
        !/^[A-Za-z0-9_]{1,16}$/.test(profile.profileName) || profile.profileName.toLowerCase() !== data.name.toLowerCase() ||
        typeof profile.timestamp !== "number" || !Number.isSafeInteger(profile.timestamp) ||
        profile.timestamp > Date.now() + 300000 || Date.now() - profile.timestamp > 86400000) return null;
    return { id: data.uuid, name: profile.profileName };
  } catch { return null; }
}
