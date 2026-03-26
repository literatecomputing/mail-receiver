# frozen_string_literal: true
require_relative "../../lib/mail_receiver/discourse_mail_receiver"

RSpec.describe DiscourseMailReceiver do
  let(:recipient) { "eviltrout@example.com" }
  let(:mail) { "some body" }

  it "raises an error without a recipient" do
    expect { described_class.new(file_for(:standard), nil, mail) }.to raise_error(
      MailReceiverBase::ReceiverException,
    )
  end

  it "raises an error without mail" do
    expect { described_class.new(file_for(:standard), recipient, nil) }.to raise_error(
      MailReceiverBase::ReceiverException,
    )

    expect { described_class.new(file_for(:standard), recipient, "") }.to raise_error(
      MailReceiverBase::ReceiverException,
    )
  end

  it "has a backwards compatible endpoint" do
    receiver = described_class.new(file_for(:standard_deprecated), recipient, mail)
    expect(receiver.endpoint).to eq("https://localhost:8080/mail-me")
  end

  it "has the correct endpoint" do
    receiver = described_class.new(file_for(:standard), "eviltrout@example.com", "test mail")
    expect(receiver.endpoint).to eq("https://localhost:8080/admin/email/handle_mail")
  end

  it "can process mail" do
    expect_any_instance_of(Net::HTTP).to receive(:request) do |http|
      Net::HTTPSuccess.new(http, 200, "OK")
    end

    receiver = described_class.new(file_for(:standard), "eviltrout@example.com", "test mail")
    expect(receiver.process).to eq(:success)
  end

  it "returns failure on HTTP error" do
    expect_any_instance_of(Net::HTTP).to receive(:request) do |http|
      Net::HTTPServerError.new(http, 500, "Error")
    end

    receiver = described_class.new(file_for(:standard), "eviltrout@example.com", "test mail")
    expect(receiver.process).to eq(:failure)
  end

  describe ".env_file_for_recipient" do
    let(:fixtures_dir) { File.expand_path("../fixtures", __dir__) }

    it "returns domain-specific file when it exists" do
      allow(File).to receive(:exist?).and_call_original
      domain_file = "#{fixtures_dir}/mail-receiver-environment-site1.example.com.json"
      allow(File).to receive(:exist?).with(domain_file).and_return(true)
      expect(described_class.env_file_for_recipient("user@site1.example.com", fixtures_dir)).to eq(
        domain_file,
      )
    end

    it "falls back to global env file when domain-specific file doesn't exist" do
      allow(File).to receive(:exist?).and_call_original
      domain_file = "#{fixtures_dir}/mail-receiver-environment-unknown.com.json"
      allow(File).to receive(:exist?).with(domain_file).and_return(false)
      expect(described_class.env_file_for_recipient("user@unknown.com", fixtures_dir)).to eq(
        "#{fixtures_dir}/mail-receiver-environment.json",
      )
    end

    it "handles recipients without an @ sign" do
      allow(File).to receive(:exist?).and_call_original
      allow(File).to receive(:exist?).with(
        "#{fixtures_dir}/mail-receiver-environment-.json",
      ).and_return(false)
      expect(described_class.env_file_for_recipient("no-at-sign", fixtures_dir)).to eq(
        "#{fixtures_dir}/mail-receiver-environment.json",
      )
    end
  end
end
