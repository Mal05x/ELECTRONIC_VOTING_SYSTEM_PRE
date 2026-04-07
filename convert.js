const fs = require('fs');
const data = require('./realistic_774_polling_units.json');

let sql = "INSERT INTO polling_units (name, code, lga_id, capacity) VALUES\n";

const values = data.map((pu, index) => {
    const safeName = pu.name.replace(/'/g, "''");

    // THE FIX: Force the code to be globally unique by appending the index
    // Example: "AB/AB/001-0", "AB/AB/001-1", etc.
    const uniqueCode = `${pu.code}-${index}`;

    return `('${safeName}', '${uniqueCode}', ${pu.lgaId}, ${pu.capacity})`;
});

sql += values.join(",\n") + ";\n";

fs.writeFileSync('V12__Seed_Polling_Units.sql', sql);
console.log("SQL file generated successfully with unique codes!");