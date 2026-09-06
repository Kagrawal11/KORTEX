import { useState } from 'react';
import { Mail } from 'lucide-react';
import EmailReportDialog from './EmailReportDialog';
import type { EmailReportType } from '../types';

interface EmailReportButtonProps {
  reportType: EmailReportType;
  runId: number;
}

/** Drop-in action for any report page's toolbar — owns the dialog's open state so each page needs only one line. */
export default function EmailReportButton({ reportType, runId }: EmailReportButtonProps) {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <>
      <button className="btn btn-secondary" onClick={() => setIsOpen(true)}>
        <Mail size={16} /> Email Report
      </button>
      {isOpen && <EmailReportDialog reportType={reportType} runId={runId} onClose={() => setIsOpen(false)} />}
    </>
  );
}
