/** Types mirror backend DuplicateModels / MergeModels JSON contracts. */

export type ContactChannel = {
  id?: string;
  channelType: string;
  value: string;
  valueNormalized?: string;
  primary: boolean;
  label?: string | null;
  status?: string;
};

export type ContactSummary = {
  id: string;
  displayName: string;
  status: string;
  primaryEmail?: string | null;
  primaryPhone?: string | null;
  customFields?: Record<string, unknown>;
  channels: ContactChannel[];
  createdAt?: string;
  updatedAt?: string;
  archivedAt?: string | null;
};

export type ResolvedMatch = {
  matchType: string;
  channelType: string;
  channelIdA: string;
  channelIdB: string;
  valueNormalized: string;
  valueRawA: string;
  valueRawB: string;
  stillValid: boolean;
};

export type CandidateSummary = {
  id: string;
  contactIdA: string;
  contactIdB: string;
  matchTypes: string[];
  confidence: string;
  score: number;
  mergeAllowed: boolean;
  status: string;
  detectedAt: string;
  lastDetectedAt: string;
  stale: boolean;
};

export type CandidateDetail = {
  id: string;
  workspaceId: string;
  contactIdA: string;
  contactIdB: string;
  contactA: ContactSummary | null;
  contactB: ContactSummary | null;
  matchTypes: string[];
  confidence: string;
  score: number;
  mergeAllowed: boolean;
  status: string;
  matches: ResolvedMatch[];
  detectedAt: string;
  lastDetectedAt: string;
  dismissedAt?: string | null;
  dismissReason?: string | null;
  stale: boolean;
};

export type CandidatePage = {
  items: CandidateSummary[];
  page: number;
  size: number;
  hasNext: boolean;
};

export type ScanAccepted = {
  scanId: string;
  status: string;
};

export type FieldDiff = {
  fieldKey: string;
  survivorValue: unknown;
  loserValue: unknown;
  resultValue: unknown;
  source: string;
};

export type ChannelDisposition = {
  channelId: string;
  contactId: string;
  channelType: string;
  valueNormalized: string;
  valueRaw: string;
  status: string;
  action: string;
  primaryAfterMerge: boolean;
};

export type IntroducedAddress = {
  channelType: string;
  storageType: string;
  channelId: string;
  valueNormalized: string;
  valueRaw: string;
};

export type ConsentCellDiff = {
  channelType: string;
  purpose: string;
  survivorBefore: string;
  loserBefore: string;
  after: string;
  changed: boolean;
  requiresFreshConsent: boolean;
};

export type GroupChange = {
  groupId: string;
  groupType: string;
  action: string;
};

export type ShareRevokePreview = {
  shareId: string;
  sourceWorkspaceId: string;
  targetWorkspaceId: string;
  permission: string;
};

export type BlockingConflict = {
  code: string;
  message: string;
};

export type MergePreview = {
  candidateId: string;
  survivorContactId: string;
  loserContactId: string;
  fields: FieldDiff[];
  channels: ChannelDisposition[];
  introducedAddresses: IntroducedAddress[];
  consent: ConsentCellDiff[];
  groups: GroupChange[];
  sharesToRevoke: ShareRevokePreview[];
  blockingConflicts: BlockingConflict[];
  mergeAllowed: boolean;
  previewHash: string;
  stale: boolean;
};

export type FieldChoice = { fieldKey: string; source: string };
export type PrimaryChoice = { channelType: string; channelId: string };

export type MergeItemView = {
  id: string;
  itemType: string;
  itemKey: string;
  action: string;
  before: Record<string, unknown>;
  after: Record<string, unknown>;
};

export type MergeView = {
  id: string;
  workspaceId: string;
  survivorContactId: string;
  loserContactId: string;
  status: string;
  idempotencyKey: string;
  requestHash: string;
  previewHash: string;
  actorId: string;
  completedAt: string;
  items: MergeItemView[];
};

export type MergePage = {
  items: MergeView[];
  page: number;
  size: number;
  hasNext: boolean;
};

export type ApiErrorBody = {
  code?: string;
  message?: string;
  traceId?: string;
};
