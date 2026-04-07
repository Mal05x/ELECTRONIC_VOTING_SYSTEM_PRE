const fs = require('fs');
const data = require('./realistic_774_polling_units.json');

let sql = "INSERT INTO polling_units (name, code, lga_id, capacity) VALUES\n";

const values = data.map(pu => {
    // Escaping single quotes in names (e.g., Jama'are)
    const safeName = pu.name.replace(/'/g, "''");
    return `('${safeName}', '${pu.code}', ${pu.lgaId}, ${pu.capacity})`;
});

sql += values.join(",\n") + ";\n";

fs.writeFileSync('V12__Seed_Polling_Units.sql', sql);
console.log("SQL file generated successfully!");