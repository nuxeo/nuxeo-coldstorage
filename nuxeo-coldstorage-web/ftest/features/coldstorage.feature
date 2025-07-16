Feature: Cold Storage

  Background:
    Given user "Jack" exists in group "members"

  Scenario: Give WriteColdStorage permission to user
    Given I login as "Administrator"
    And I have a File document
    And This document has file "sample.png" for content
    When I browse to the document
    And I give WriteColdStorage permission to "Jack" on the document
    Then I can see that "Jack" has the WriteColdStorage permission
    
  Scenario: User with WriteColdStorage permission can send file to cold storage
    Given I login as "Jack"
    And I have a File document
    And This document has file "sample.png" for content
    And I have permission WriteColdStorage for this document
    When I browse to the document
    And I click the Send file to cold storage action button
    Then I can see the "Send" confirmation dialog
    When I click the confirm button in the "Send" confirmation dialog
    Then I can see the file is stored in cold storage
    And I cannot see the Remove button

  Scenario: User can Cancel the send file to cold storage action in confirmation dialog
    Given I login as "Jack"
    And I have a File document
    And This document has file "sample.png" for content
    And I have permission WriteColdStorage for this document
    When I browse to the document
    And I click the Send file to cold storage action button
    Then I can see the "Send" confirmation dialog
    When I click the cancel button in the "Send" confirmation dialog
    Then I can see the file is not stored in cold storage

  Scenario: User with ReadWrite permission can't send file to cold storage
    Given I login as "Jack"
    And I have a File document
    And This document has file "sample.png" for content
    And I have permission ReadWrite for this document
    When I browse to the document
    Then I cannot see the Send file to cold storage button

  Scenario: User can send multiple files to cold storage in Workspace view
    Given I login as "Jack"
    And I have the following documents
      | doctype    | title    | path                | file        |
      | Workspace  | ws       | /default-domain     |             |
      | File       | sample1  | /default-domain/ws  | sample.png  |
      | File       | sample2  | /default-domain/ws  | sample.png  |
      | File       | sample3  | /default-domain/ws  | sample.png  |
    And I have the following permissions to the documents
      | permission        | path                        |
      | WriteColdStorage  | /default-domain/ws/sample1  |
      | WriteColdStorage  | /default-domain/ws/sample2  |
      | WriteColdStorage  | /default-domain/ws/sample3  |
    When I browse to the document with path "/default-domain/ws"
    And I select the "sample1" document
    And I select the "sample2" document
    And I select the "sample3" document
    And I move the files to cold storage
    When I browse to the document with path "/default-domain/ws/sample1"
    Then I can see the file is stored in cold storage
    And I cannot see the Remove button
    When I browse to the document with path "/default-domain/ws/sample2"
    Then I can see the file is stored in cold storage
    When I browse to the document with path "/default-domain/ws/sample3"
    Then I can see the file is stored in cold storage

  Scenario: User without WriteColdStorage permission on all selected documents cannot send them to cold storage
    Given I login as "Jack"
    And I have the following documents
      | doctype    | title    | path                | file        |
      | Workspace  | ws       | /default-domain     |             |
      | File       | sample1  | /default-domain/ws  | sample.png  |
      | File       | sample2  | /default-domain/ws  | sample.png  |
      | File       | sample3  | /default-domain/ws  | sample.png  |
    And I have the following permissions to the documents
      | permission        | path                        |
      | WriteColdStorage  | /default-domain/ws/sample1  |
      | WriteColdStorage  | /default-domain/ws/sample2  |
      | ReadWrite         | /default-domain/ws/sample3  |
    When I browse to the document with path "/default-domain/ws"
    And I select the "sample1" document
    And I select the "sample2" document
    Then I can see the Send the selected files to cold storage action button
    And I select the "sample3" document
    Then I cannot see the Send the selected files to cold storage action button

  Scenario: User with WriteColdStorage permission can Restore file from cold storage
    Given I login as "Jack"
    And I have a File document
    And This document has file "sample.png" for content
    And I have permission WriteColdStorage for this document
    When I browse to the document
    And I click the Send file to cold storage action button
    Then I can see the "Send" confirmation dialog
    When I click the confirm button in the "Send" confirmation dialog
    Then I can see the file is stored in cold storage
    When I click the Restore file from cold storage action button
    Then I can see the "Restore" confirmation dialog
    When I click the confirm button in the "Restore" confirmation dialog
    Then I can see the file is being retrieved

  Scenario: User with WriteColdStorage permission can Retrieve file from cold storage
    Given I login as "Jack"
    And I have a File document
    And This document has file "sample.png" for content
    And I have permission WriteColdStorage for this document
    When I browse to the document
    And I click the Send file to cold storage action button
    Then I can see the "Send" confirmation dialog
    When I click the confirm button in the "Send" confirmation dialog
    Then I can see the file is stored in cold storage
    When I click the Retrieve file from cold storage button
    Then I can see the "Retrieve" confirmation dialog
    When I click the confirm button in the "Retrieve" confirmation dialog
    Then I can see the file is being retrieved
