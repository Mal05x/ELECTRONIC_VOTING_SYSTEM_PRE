const fs = require('fs');

const pollingUnits = [];

// Loop through all 774 LGAs
for (let i = 1; i <= 774; i++) {
  // Format the LGA ID to be 3 digits (e.g., 001, 045, 350)
  const formattedId = i.toString().padStart(3, '0');

  pollingUnits.push({
    name: `Ward 001 (LGA ${i})`,
    code: `NG/LGA-${formattedId}/001`,
    lgaId: i,
    capacity: 500
  });
}

// Write the array to a JSON file
fs.writeFileSync('all_774_polling_units.json', JSON.stringify(pollingUnits, null, 2));

console.log("✅ Successfully generated all_774_polling_units.json with 774 records!");