const { expect } = require("@playwright/test");

/** Fail the test on any page error or console error. */
function watchErrors(page) {
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error") errors.push(m.text()); });
  return () => expect(errors, "browser errors").toEqual([]);
}

async function search(page, { source = "logs", q = "", range = "1h" } = {}) {
  const params = new URLSearchParams({ source, q, range });
  await page.goto(`/#/search?${params}`);
  await expect(page.getByTestId("total")).toBeVisible();
}

const total = async (page) => Number((await page.getByTestId("total").textContent()).replace(/,/g, ""));

module.exports = { watchErrors, search, total };
