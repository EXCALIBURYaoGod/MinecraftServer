/** 极简彩色终端日志。 */

const useColor = process.stdout.isTTY && !process.env.NO_COLOR;
const C = {
  reset: '\x1b[0m',
  dim: '\x1b[2m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  cyan: '\x1b[36m',
  bold: '\x1b[1m',
};

function paint(color, text) {
  return useColor ? `${color}${text}${C.reset}` : text;
}

export const log = {
  info(msg) { console.log(msg); },
  ok(msg) { console.log(paint(C.green, '\u2713 ' + msg)); },
  warn(msg) { console.log(paint(C.yellow, '\u26a0 ' + msg)); },
  error(msg) { console.error(paint(C.red, '\u2717 ' + msg)); },
  dim(msg) { console.log(paint(C.dim, msg)); },
  title(msg) { console.log(paint(C.cyan + C.bold, msg)); },
  code(msg) {
    console.log(paint(C.cyan + C.bold, '  ' + msg));
  },
};