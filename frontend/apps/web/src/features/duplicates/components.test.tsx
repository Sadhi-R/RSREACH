import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ChannelDispositionList, ConsentImpactList } from "./components";

describe("merge preview components", () => {
  it("renders channel dispositions", () => {
    render(
      <ChannelDispositionList
        channels={[
          {
            channelId: "1",
            contactId: "a",
            channelType: "EMAIL",
            valueNormalized: "a@example.com",
            valueRaw: "a@example.com",
            status: "ACTIVE",
            action: "MOVED",
            primaryAfterMerge: true
          }
        ]}
      />
    );
    expect(screen.getByText(/moved to survivor/i)).toBeInTheDocument();
  });

  it("renders consent downgrade warnings", () => {
    render(
      <ConsentImpactList
        items={[
          {
            channelType: "EMAIL",
            purpose: "MARKETING",
            survivorBefore: "OPTED_IN",
            loserBefore: "UNKNOWN",
            after: "UNKNOWN",
            changed: true,
            requiresFreshConsent: true
          }
        ]}
      />
    );
    expect(screen.getByText(/fresh consent required/i)).toBeInTheDocument();
  });
});
