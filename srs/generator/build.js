// Writes the three Word documents into the srs folder. Each document is built twice so that "Table n" and
// "Figure n" references, which may point forward, resolve to the numbers of the finished document.
//   npm install && npm run build      (needs Python with matplotlib for the figures)
const fs = require("fs");
const path = require("path");
const { Packer } = require("docx");

const documents = [
  { module: "./srs", file: "SRS_GPS_Processing_Layer.docx" },
  { module: "./schema", file: "DB_Schema_GPS_Tracking.docx" },
  { module: "./rules", file: "SRS_Rules_and_Regulations.docx" },
];

async function main() {
  const only = process.argv[2];
  for (const { module, file } of documents) {
    if (only && !file.toLowerCase().includes(only.toLowerCase())) continue;
    const { build } = require(module);
    build();
    const doc = build();
    const target = path.join(__dirname, "..", file);
    fs.writeFileSync(target, await Packer.toBuffer(doc));
    console.log("wrote", target);
  }
}

main().catch((problem) => {
  console.error(problem);
  process.exit(1);
});
