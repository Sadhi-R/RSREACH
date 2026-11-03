import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import {
  ContactGroupDetailPage,
  ContactGroupFormPage,
  ContactGroupsDirectoryPage
} from "./contact-groups";
import { ContactImportPage } from "./contact-import";
import { ContactDetailPage, ContactFormPage, ContactsDirectoryPage } from "./contacts";
import {
  CandidateDetailPage,
  DuplicatesDirectoryPage,
  MergeDetailPage,
  MergeHistoryPage,
  MergeWizardPage
} from "./features/duplicates";
import { CampaignDetailPage, CampaignsDirectoryPage } from "./features/inbox/CampaignsPage";
import { InboxDetailPage, InboxDirectoryPage } from "./features/inbox/InboxPages";
import { IntegrationsPage } from "./features/inbox/IntegrationsPage";
import {
  BillingInvoicesPage,
  BillingOverviewPage,
  BillingPlansPage,
  InvoiceDetailPage,
  PaymentsPage,
  PlatformBillingPage
} from "./features/billing/BillingPages";
import {
  AutomationDetailPage,
  AutomationFormPage,
  AutomationsDirectoryPage
} from "./features/automations/AutomationsPages";
import { NotificationsPage } from "./features/notifications/NotificationsPage";
import { PlatformGrantsPage, PlatformAuditPage } from "./features/platform/PlatformAdminPages";
import { AppShell } from "./components/AppShell";
import {
  CustomerDashboardPage,
  CustomerLoginPage,
  CustomerRegisterPage,
  HomePage,
  InviteAcceptPage,
  PasswordResetConfirmPage,
  PasswordResetRequestPage,
  PlatformDashboardPage,
  RequireCustomer,
  RequireStaff,
  StaffLoginPage
} from "./pages";

export function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Public routes */}
        <Route path="/" element={<HomePage />} />
        <Route path="/app/register" element={<CustomerRegisterPage />} />
        <Route path="/app/login" element={<CustomerLoginPage />} />
        <Route path="/app/password-reset" element={<PasswordResetRequestPage />} />
        <Route path="/app/password-reset/confirm" element={<PasswordResetConfirmPage />} />
        <Route path="/app/invitations/accept" element={<InviteAcceptPage />} />

        {/* Customer protected layout — RequireCustomer + AppShell as layout */}
        <Route
          element={
            <RequireCustomer>
              <AppShell />
            </RequireCustomer>
          }
        >
          <Route path="/app" element={<CustomerDashboardPage />} />
          <Route path="/app/contacts" element={<ContactsDirectoryPage />} />
          <Route path="/app/contacts/import" element={<ContactImportPage />} />
          <Route path="/app/contacts/new" element={<ContactFormPage />} />
          <Route path="/app/contacts/:contactId/edit" element={<ContactFormPage />} />
          <Route path="/app/contacts/:contactId" element={<ContactDetailPage />} />
          <Route path="/app/contact-groups" element={<ContactGroupsDirectoryPage />} />
          <Route path="/app/contact-groups/new" element={<ContactGroupFormPage />} />
          <Route path="/app/contact-groups/:groupId" element={<ContactGroupDetailPage />} />
          <Route path="/app/contact-duplicates" element={<DuplicatesDirectoryPage />} />
          <Route path="/app/contact-duplicates/:candidateId" element={<CandidateDetailPage />} />
          <Route
            path="/app/contact-duplicates/:candidateId/merge"
            element={<MergeWizardPage />}
          />
          <Route path="/app/merges" element={<MergeHistoryPage />} />
          <Route path="/app/merges/:mergeId" element={<MergeDetailPage />} />
          <Route path="/app/inbox" element={<InboxDirectoryPage />} />
          <Route path="/app/inbox/:conversationId" element={<InboxDetailPage />} />
          <Route path="/app/integrations" element={<IntegrationsPage />} />
          <Route path="/app/campaigns" element={<CampaignsDirectoryPage />} />
          <Route path="/app/campaigns/:campaignId" element={<CampaignDetailPage />} />
          {/* Billing */}
          <Route path="/app/billing" element={<BillingOverviewPage />} />
          <Route path="/app/billing/plans" element={<BillingPlansPage />} />
          <Route path="/app/billing/invoices" element={<BillingInvoicesPage />} />
          <Route path="/app/billing/invoices/:invoiceId" element={<InvoiceDetailPage />} />
          <Route path="/app/billing/payments" element={<PaymentsPage />} />
          {/* Automations */}
          <Route path="/app/automations" element={<AutomationsDirectoryPage />} />
          <Route path="/app/automations/new" element={<AutomationFormPage />} />
          <Route path="/app/automations/:workflowId/edit" element={<AutomationFormPage />} />
          <Route path="/app/automations/:workflowId" element={<AutomationDetailPage />} />
          {/* Notifications */}
          <Route path="/app/notifications" element={<NotificationsPage />} />
        </Route>

        {/* Staff / platform routes */}
        <Route path="/platform/login" element={<StaffLoginPage />} />
        <Route
          path="/platform"
          element={
            <RequireStaff>
              <PlatformDashboardPage />
            </RequireStaff>
          }
        />
        <Route
          path="/platform/billing"
          element={
            <RequireStaff>
              <PlatformBillingPage />
            </RequireStaff>
          }
        />
        <Route
          path="/platform/grants"
          element={
            <RequireStaff>
              <PlatformGrantsPage />
            </RequireStaff>
          }
        />
        <Route
          path="/platform/audit"
          element={
            <RequireStaff>
              <PlatformAuditPage />
            </RequireStaff>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
