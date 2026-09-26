const required = (name: string): string => {
  const value = process.env[name];
  if (!value) throw new Error(`Missing required environment variable ${name}`);
  return value;
};

export const config = {
  port: Number(process.env.PORT ?? 4000),
  get jwtSecret(): string {
    const secret = required("JWT_SECRET");
    if (secret.length < 32) throw new Error("JWT_SECRET must be at least 32 characters");
    return secret;
  },
  jwtTtl: process.env.JWT_TTL ?? "30d",
  defaultRegion: "IN" as const,
};
