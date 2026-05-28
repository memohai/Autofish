#!/usr/bin/env node

import path from 'node:path';

function pathEntries() {
  return (process.env.PATH || '')
    .split(path.delimiter)
    .filter(Boolean)
    .map((entry) => path.resolve(entry));
}

function npmGlobalBin() {
  const prefix = process.env.npm_config_prefix;
  if (!prefix) return null;
  return process.platform === 'win32' ? path.resolve(prefix) : path.resolve(prefix, 'bin');
}

function isGlobalInstall() {
  return process.env.npm_config_global === 'true';
}

function main() {
  if (process.env.AF_SUPPRESS_POSTINSTALL === '1') return;

  const lines = ['', 'Autofish CLI installed.'];
  const binDir = npmGlobalBin();
  if (isGlobalInstall() && binDir && !pathEntries().includes(binDir)) {
    lines.push(`npm global bin is not in PATH: ${binDir}`);
    lines.push(`Add it to your shell PATH, then run: af --help`);
  } else {
    lines.push('Run: af --help');
  }
  lines.push('Shell completion: af completion <bash|zsh|fish|powershell|elvish>');
  lines.push('');

  console.log(lines.join('\n'));
}

main();
