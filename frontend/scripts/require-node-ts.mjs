// Guard for `npm run test:unit`: the draft saver tests are .ts files run directly by Node,
// which needs built-in type stripping (on by default from Node 22.18 / 23.6).
const [major, minor] = process.versions.node.split('.').map(Number)
const ok = major > 23 || (major === 23 && minor >= 6) || (major === 22 && minor >= 18)
if (!ok) {
  console.error(`test:unit needs Node 22.18+ (found ${process.versions.node}). The app itself builds with Node 20.19+.`)
  process.exit(1)
}
