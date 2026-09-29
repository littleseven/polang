import XCTest
@testable import PoLang

/// 详情保存 `applyPersonEdit` 语义用例——对标 Android `PersonRepositoryTest` 四条，
/// 另补 SubjectNotFound 与「空名=保持原名」两条 iOS 契约（spec person.yaml §7）。
final class PersonApplyEditTests: XCTestCase {

    private var db: TagDatabase!
    private var repo: PersonRepository!

    override func setUp() {
        super.setUp()
        let tmp = NSTemporaryDirectory() + "person_apply_edit_\(UUID().uuidString).db"
        db = TagDatabase(dbPath: tmp)
        repo = PersonRepository(db: db)
    }

    override func tearDown() {
        repo = nil
        db = nil
        super.tearDown()
    }

    private func insert(_ name: String?, isSelf: Bool) -> Int64 {
        db.insertPerson(name: name, coverMediaId: nil, faceCount: 1, isSelf: isSelf)
    }

    // 1. 无「这是我」→ SelfNotDeclared 且声明不落库（静默丢弃根因回归）
    func testApplyEditWithoutSelfReturnsSelfNotDeclaredAndWritesNoRelation() {
        let child = insert("小宝", isSelf: false)

        let result = repo.applyPersonEdit(
            personId: child, name: "小宝", relation: "CHILD", customLabel: "", isSelf: false)

        XCTAssertEqual(result, .selfNotDeclared, "结果必须透传给调用方，不允许静默吞掉")
        // 事后补设 self 再查：被拒的声明不应留有任何落库痕迹
        _ = insert("我", isSelf: true)
        XCTAssertNil(db.relationToSelf(personId: child), "被拒的声明不落库")
    }

    // 2. 有「这是我」→ Declared 且关系+自定义称呼落库
    func testApplyEditWithSelfReturnsDeclaredAndPersistsRelation() {
        _ = insert("我", isSelf: true)
        let child = insert("小宝", isSelf: false)

        let result = repo.applyPersonEdit(
            personId: child, name: "小宝", relation: "CHILD", customLabel: "二儿子", isSelf: false)

        XCTAssertEqual(result, .declared)
        let relation = db.relationToSelf(personId: child)
        XCTAssertEqual(relation?.predicate, "CHILD")
        XCTAssertEqual(relation?.customLabel, "二儿子")
    }

    // 3. relation=nil → 返回 nil 且清除既有关系
    func testApplyEditWithNilRelationReturnsNilAndClearsRelations() {
        _ = insert("我", isSelf: true)
        let child = insert("小宝", isSelf: false)
        _ = repo.declareRelation(personId: child, predicate: "CHILD", customLabel: nil)

        let result = repo.applyPersonEdit(
            personId: child, name: "小宝", relation: nil, customLabel: "", isSelf: false)

        XCTAssertNil(result, "清除路径无声明动作，返回 nil")
        XCTAssertNil(db.relationToSelf(personId: child))
    }

    // 4. 改名与声明同一保存通路单次写入生效
    func testApplyEditRenamesInSameWriteAsRelationDeclaration() {
        _ = insert("我", isSelf: true)
        let child = insert("旧名", isSelf: false)

        let result = repo.applyPersonEdit(
            personId: child, name: "新名", relation: "FRIEND", customLabel: "", isSelf: false)

        XCTAssertEqual(result, .declared)
        XCTAssertEqual(db.personRow(child)?.name, "新名", "新名单次写入即生效")
    }

    // 5.（iOS 契约补充）目标人物不存在 → SubjectNotFound
    func testApplyEditMissingSubjectReturnsSubjectNotFound() {
        let result = repo.applyPersonEdit(
            personId: 999_999, name: "x", relation: "FRIEND", customLabel: "", isSelf: false)
        XCTAssertEqual(result, .subjectNotFound)
    }

    // 6.（iOS 契约补充）空名 = 保持原名不变（编辑页不支持取消命名）
    func testApplyEditEmptyNameKeepsExistingName() {
        _ = insert("我", isSelf: true)
        let child = insert("旧名", isSelf: false)

        _ = repo.applyPersonEdit(
            personId: child, name: "", relation: nil, customLabel: "", isSelf: false)

        XCTAssertEqual(db.personRow(child)?.name, "旧名", "空名=保持原名，不得清名")
    }
}
