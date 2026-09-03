#!/usr/bin/env node
import { buildCli } from '../src/cli.js';
import { log } from '../src/logger.js';

const program = buildCli();

program.parseAsync(process.argv).catch((err) => {
  log.error(err && err.message ? err.message : String(err));
  process.exitCode = 1;
});