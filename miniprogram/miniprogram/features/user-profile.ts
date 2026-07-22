const MIN_NICKNAME_LENGTH = 2;
const MAX_NICKNAME_LENGTH = 32;

export function normalizeProfileNickname(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

export function validateProfileNickname(value: unknown): string {
  const nickname = normalizeProfileNickname(value);
  if (!nickname) {
    return "请选择或输入微信昵称";
  }
  const codePoints = Array.from(nickname);
  if (codePoints.length < MIN_NICKNAME_LENGTH) {
    return "昵称至少需要 2 个字符";
  }
  if (codePoints.length > MAX_NICKNAME_LENGTH) {
    return "昵称不能超过 32 个字符";
  }
  if (codePoints.some((character) => {
    const code = character.codePointAt(0) ?? 0;
    return code <= 31 || (code >= 127 && code <= 159);
  })) {
    return "昵称包含不支持的字符";
  }
  return "";
}

export function profileHasChanges(
  nickname: unknown,
  originalNickname: unknown,
  pendingAvatarPath: unknown
): boolean {
  return Boolean(
    normalizeProfileNickname(nickname) !== normalizeProfileNickname(originalNickname) ||
    (typeof pendingAvatarPath === "string" && pendingAvatarPath.trim())
  );
}
