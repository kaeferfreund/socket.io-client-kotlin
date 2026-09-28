// Kotlin-specific fixture: stands in for a version-manager shim (Volta, asdf,
// mise). The process started as `node` only runs server.js as its child, and a
// signal to it does not reach that child. FixtureServerTest pins that closing
// the fixture stops the server behind it too.
import { spawn } from "node:child_process";

const server = spawn(process.execPath, ["server.js"], { stdio: ["ignore", "inherit", "inherit"] });
server.on("exit", (code) => process.exit(code ?? 1));
